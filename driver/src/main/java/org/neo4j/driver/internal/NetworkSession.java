/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.driver.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.neo4j.driver.internal.retry.RetryDecision;
import org.neo4j.driver.internal.retry.RetryLogic;
import org.neo4j.driver.internal.spi.Connection;
import org.neo4j.driver.internal.spi.ConnectionProvider;
import org.neo4j.driver.internal.spi.PooledConnection;
import org.neo4j.driver.internal.types.InternalTypeSystem;
import org.neo4j.driver.v1.AccessMode;
import org.neo4j.driver.v1.Logger;
import org.neo4j.driver.v1.Logging;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.Statement;
import org.neo4j.driver.v1.StatementResult;
import org.neo4j.driver.v1.Transaction;
import org.neo4j.driver.v1.TransactionWork;
import org.neo4j.driver.v1.Value;
import org.neo4j.driver.v1.Values;
import org.neo4j.driver.v1.exceptions.ClientException;
import org.neo4j.driver.v1.types.TypeSystem;

import static org.neo4j.driver.v1.Values.value;

public class NetworkSession implements Session, SessionResourcesHandler
{
    private final ConnectionProvider connectionProvider;
    private final AccessMode mode;
    private final RetryLogic<RetryDecision> retryLogic;
    protected final Logger logger;

    private String lastBookmark;
    private PooledConnection currentConnection;
    private ExplicitTransaction currentTransaction;

    private final AtomicBoolean isOpen = new AtomicBoolean( true );

    public NetworkSession( ConnectionProvider connectionProvider, AccessMode mode, RetryLogic<RetryDecision> retryLogic,
            Logging logging )
    {
        this.connectionProvider = connectionProvider;
        this.mode = mode;
        this.retryLogic = retryLogic;
        this.logger = logging.getLog(Session.class.getName());
    }

    @Override
    public StatementResult run( String statementText )
    {
        return run( statementText, Values.EmptyMap );
    }

    @Override
    public StatementResult run( String statementText, Map<String,Object> statementParameters )
    {
        Value params = statementParameters == null ? Values.EmptyMap : value( statementParameters );
        return run( statementText, params );
    }

    @Override
    public StatementResult run( String statementTemplate, Record statementParameters )
    {
        Value params = statementParameters == null ? Values.EmptyMap : value( statementParameters.asMap() );
        return run( statementTemplate, params );
    }

    @Override
    public StatementResult run( String statementText, Value statementParameters )
    {
        return run( new Statement( statementText, statementParameters ) );
    }

    @Override
    public StatementResult run( Statement statement )
    {
        ensureSessionIsOpen();
        ensureNoOpenTransactionBeforeRunningSession();

        syncAndCloseCurrentConnection();
        currentConnection = acquireConnection( mode );

        return run( currentConnection, statement, this );
    }

    public static StatementResult run( Connection connection, Statement statement,
            SessionResourcesHandler resourcesHandler )
    {
        InternalStatementResult result = new InternalStatementResult( connection, resourcesHandler, null, statement );
        connection.run( statement.text(), statement.parameters().asMap( Values.ofValue() ),
                result.runResponseCollector() );
        connection.pullAll( result.pullAllResponseCollector() );
        connection.flush();
        return result;
    }

    @Override
    public synchronized void reset()
    {
        ensureSessionIsOpen();
        ensureNoUnrecoverableError();

        if ( currentTransaction != null )
        {
            currentTransaction.markToClose();
            updateLastBookmarkFrom( currentTransaction );
            currentTransaction = null;
        }
        if ( currentConnection != null )
        {
            currentConnection.resetAsync();
        }
    }

    @Override
    public boolean isOpen()
    {
        return isOpen.get();
    }

    @Override
    public void close()
    {
        // Use atomic operation to protect from closing the connection twice (putting back to the pool twice).
        if ( !isOpen.compareAndSet( true, false ) )
        {
            throw new ClientException( "This session has already been closed." );
        }

        synchronized ( this )
        {
            if ( currentTransaction != null )
            {
                try
                {
                    currentTransaction.close();
                }
                catch ( Throwable e )
                {
                    // Best-effort
                    logger.warn( "WARNING: Failed to close tx due to error: " + e.toString() );
                }
            }
        }

        syncAndCloseCurrentConnection();
    }

    @Override
    public synchronized Transaction beginTransaction()
    {
        return beginTransaction( mode );
    }

    @Override
    public synchronized Transaction beginTransaction( String bookmark )
    {
        lastBookmark = bookmark;
        return beginTransaction();
    }

    @Override
    public <T> T readTransaction( TransactionWork<T> work )
    {
        return transaction( AccessMode.READ, work );
    }

    @Override
    public <T> T writeTransaction( TransactionWork<T> work )
    {
        return transaction( AccessMode.WRITE, work );
    }

    void setLastBookmark( String bookmark )
    {
        lastBookmark = bookmark;
    }

    @Override
    public String lastBookmark()
    {
        return lastBookmark;
    }

    @Override
    public TypeSystem typeSystem()
    {
        return InternalTypeSystem.TYPE_SYSTEM;
    }

    @Override
    public synchronized void onResultConsumed()
    {
        closeCurrentConnection();
    }

    @Override
    public synchronized void onTransactionClosed( ExplicitTransaction tx )
    {
        if ( currentTransaction != null && currentTransaction == tx )
        {
            closeCurrentConnection();
            updateLastBookmarkFrom( currentTransaction );
            currentTransaction = null;
        }
    }

    @Override
    public synchronized void onConnectionError( boolean recoverable )
    {
        // must check if transaction has been closed
        if ( currentTransaction != null )
        {
            if ( recoverable )
            {
                currentTransaction.failure();
            }
            else
            {
                currentTransaction.markToClose();
            }
        }
    }

    private <T> T transaction( AccessMode mode, TransactionWork<T> work )
    {
        RetryDecision decision = null;
        List<Throwable> errors = null;

        while ( true )
        {
            try ( Transaction tx = beginTransaction( mode ) )
            {
                T result;
                try
                {
                    result = work.execute( tx );
                }
                catch ( Throwable t )
                {
                    // mark transaction for failure if the given unit of work threw exception
                    // this will override any success marks that were made by the unit of work
                    tx.failure();
                    throw t;
                }
                // given unit of work completed successfully, mark transaction for commit
                tx.success();
                return result;
            }
            catch ( Throwable newError )
            {
                decision = retryLogic.apply( newError, decision );

                if ( decision.shouldRetry() )
                {
                    errors = recordError( newError, errors );
                }
                else
                {
                    addSuppressed( newError, errors );
                    throw newError;
                }
            }
        }
    }

    private synchronized Transaction beginTransaction( AccessMode mode )
    {
        ensureSessionIsOpen();
        ensureNoOpenTransactionBeforeOpeningTransaction();

        syncAndCloseCurrentConnection();
        currentConnection = acquireConnection( mode );

        currentTransaction = new ExplicitTransaction( currentConnection, this, lastBookmark );
        currentConnection.setResourcesHandler( this );
        return currentTransaction;
    }

    private void ensureNoUnrecoverableError()
    {
        if ( currentConnection != null && currentConnection.hasUnrecoverableErrors() )
        {
            throw new ClientException( "Cannot run more statements in the current session as an unrecoverable error " +
                                       "has happened. Please close the current session and re-run your statement in a" +
                                       " new session." );
        }
    }

    //should be called from a synchronized block
    private void ensureNoOpenTransactionBeforeRunningSession()
    {
        if ( currentTransaction != null )
        {
            throw new ClientException( "Statements cannot be run directly on a session with an open transaction;" +
                                       " either run from within the transaction or use a different session." );
        }
    }

    //should be called from a synchronized block
    private void ensureNoOpenTransactionBeforeOpeningTransaction()
    {
        if ( currentTransaction != null )
        {
            throw new ClientException( "You cannot begin a transaction on a session with an open transaction;" +
                                       " either run from within the transaction or use a different session." );
        }
    }

    private void ensureSessionIsOpen()
    {
        if ( !isOpen.get() )
        {
            throw new ClientException(
                    "No more interaction with this session is allowed " +
                    "as the current session is already closed or marked as closed. " +
                    "You get this error either because you have a bad reference to a session that has already be " +
                    "closed " +
                    "or you are trying to reuse a session that you have called `reset` on it." );
        }
    }

    private PooledConnection acquireConnection( AccessMode mode )
    {
        PooledConnection connection = connectionProvider.acquireConnection( mode );
        logger.debug( "Acquired connection " + connection.hashCode() );
        return connection;
    }

    boolean currentConnectionIsOpen()
    {
        return currentConnection != null && currentConnection.isOpen();
    }

    private void syncAndCloseCurrentConnection()
    {
        closeCurrentConnection( true );
    }

    private void closeCurrentConnection()
    {
        closeCurrentConnection( false );
    }

    private void closeCurrentConnection( boolean sync )
    {
        if ( currentConnection == null )
        {
            return;
        }

        PooledConnection connection = currentConnection;
        currentConnection = null;
        try
        {
            if ( sync && connection.isOpen() )
            {
                connection.sync();
            }
        }
        finally
        {
            connection.close();
            logger.debug( "Released connection " + connection.hashCode() );
        }
    }

    private void updateLastBookmarkFrom( ExplicitTransaction tx )
    {
        if ( tx.bookmark() != null )
        {
            lastBookmark = tx.bookmark();
        }
    }

    private static List<Throwable> recordError( Throwable error, List<Throwable> errors )
    {
        if ( errors == null )
        {
            errors = new ArrayList<>();
        }
        errors.add( error );
        return errors;
    }

    private static void addSuppressed( Throwable error, List<Throwable> suppressedErrors )
    {
        if ( suppressedErrors != null )
        {
            for ( Throwable suppressedError : suppressedErrors )
            {
                if ( error != suppressedError )
                {
                    error.addSuppressed( suppressedError );
                }
            }
        }
    }
}
