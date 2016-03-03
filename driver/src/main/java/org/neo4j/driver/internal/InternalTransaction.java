/**
 * Copyright (c) 2002-2016 "Neo Technology,"
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

import java.util.Collections;
import java.util.Map;

import org.neo4j.driver.internal.spi.Connection;
import org.neo4j.driver.internal.spi.StreamCollector;
import org.neo4j.driver.internal.types.InternalTypeSystem;
import org.neo4j.driver.v1.ResultCursor;
import org.neo4j.driver.v1.Statement;
import org.neo4j.driver.v1.Transaction;
import org.neo4j.driver.v1.TypeSystem;
import org.neo4j.driver.v1.Value;
import org.neo4j.driver.v1.exceptions.ClientException;
import org.neo4j.driver.v1.exceptions.Neo4jException;

public class InternalTransaction implements Transaction
{
    private enum State
    {
        /** The transaction is running with no explicit success or failure marked */
        ACTIVE,

        /** Running, user marked for success, meaning it'll value committed */
        MARKED_SUCCESS,

        /** User marked as failed, meaning it'll be rolled back. */
        MARKED_FAILED,

        /**
         * An error has occurred, transaction can no longer be used and no more messages will be sent for this
         * transaction.
         */
        FAILED,

        /** This transaction has successfully committed */
        SUCCEEDED,

        /** This transaction has been rolled back */
        ROLLED_BACK
    }

    private final Runnable cleanup;
    private final Connection conn;

    private State state = State.ACTIVE;

    public InternalTransaction( Connection conn, Runnable cleanup )
    {
        this.conn = conn;
        this.cleanup = cleanup;

        // Note there is no sync here, so this will just value queued locally
        conn.run( "BEGIN", Collections.<String, Value>emptyMap(), StreamCollector.NO_OP );
        conn.discardAll();
    }

    @Override
    public void success()
    {
        if ( state == State.ACTIVE )
        {
            state = State.MARKED_SUCCESS;
        }
    }

    @Override
    public void failure()
    {
        if ( state == State.ACTIVE || state == State.MARKED_SUCCESS )
        {
            state = State.MARKED_FAILED;
        }
    }

    @Override
    public void close()
    {
        try
        {
            if ( conn != null && conn.isOpen() )
            {
                if ( state == State.MARKED_SUCCESS )
                {
                    conn.run( "COMMIT", Collections.<String, Value>emptyMap(), StreamCollector.NO_OP );
                    conn.discardAll();
                    conn.sync();
                    state = State.SUCCEEDED;
                }
                else if ( state == State.MARKED_FAILED || state == State.ACTIVE )
                {
                    // If alwaysValid of the things we've put in the queue have been sent off, there is no need to
                    // do this, we could just clear the queue. Future optimization.
                    conn.run( "ROLLBACK", Collections.<String, Value>emptyMap(), StreamCollector.NO_OP );
                    conn.discardAll();
                    conn.sync();
                    state = State.ROLLED_BACK;
                }
            }
        }
        finally
        {
            cleanup.run();
        }
    }

    @Override
    @SuppressWarnings( "unchecked" )
    public ResultCursor run( String statementText, Map<String,Value> statementParameters )
    {
        ensureNotFailed();

        try
        {
            InternalResultCursor cursor = new InternalResultCursor( conn, statementText, statementParameters );
            conn.run( statementText, statementParameters, cursor.runResponseCollector() );
            conn.pullAll( cursor.pullAllResponseCollector() );
            conn.flush();
            return cursor;
        }
        catch ( Neo4jException e )
        {
            state = State.FAILED;
            throw e;
        }
    }

    @Override
    public ResultCursor run( String statementTemplate )
    {
        return run( statementTemplate, ParameterSupport.NO_PARAMETERS );
    }

    @Override
    public ResultCursor run( Statement statement )
    {
        return run( statement.template(), statement.parameters() );
    }

    @Override
    public boolean isOpen()
    {
        return state == State.ACTIVE;
    }

    private void ensureNotFailed()
    {
        if ( state == State.FAILED || state == State.MARKED_FAILED || state == State.ROLLED_BACK )
        {
            throw new ClientException(
                "Cannot run more statements in this transaction, because previous statements in the " +
                "transaction has failed and the transaction has been rolled back. Please start a new" +
                " transaction to run another statement."
            );
        }
    }

    @Override
    public TypeSystem typeSystem()
    {
        return InternalTypeSystem.TYPE_SYSTEM;
    }

    // TODO: This is wrong. This is only needed because we changed the SSM
    // to move to IDLE on any exception (so the normal `ROLLBACK` statement won't work).
    // We should change the SSM to move to some special ROLLBACK_ONLY state instead and
    // remove this code path
    public void markAsRolledBack()
    {
        state = State.ROLLED_BACK;
    }
}
