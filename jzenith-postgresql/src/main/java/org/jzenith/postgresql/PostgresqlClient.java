/**
 * Copyright © 2018 Marcus Thiesen (marcus@thiesen.org)
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
package org.jzenith.postgresql;

import com.google.common.collect.Iterables;
import io.reactiverse.pgclient.impl.ArrayTuple;
import io.reactiverse.reactivex.pgclient.*;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.Single;
import lombok.NonNull;
import org.jooq.Query;
import org.jooq.Select;
import org.jooq.SelectJoinStep;
import org.postgresql.core.NativeQuery;
import org.postgresql.core.Parser;

import javax.inject.Inject;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class PostgresqlClient {

    private final PgPool pgPool;

    @Inject
    public PostgresqlClient(PgPool pgPool) {
        this.pgPool = pgPool;
    }

    public Single<PgRowSet> execute(@NonNull Query query) {
        try {
            final NativeQuery nativeQuery = parseNativeQuery(query);

            return pgPool.rxPreparedQuery(nativeQuery.nativeSql, bindValuesToTuple(query));
        } catch (SQLException e) {
            return Single.error(e);
        }
    }

    private NativeQuery parseNativeQuery(@NonNull Query query) throws SQLException {
        final List<NativeQuery> nativeQueries = Parser.parseJdbcSql(query.getSQL(), true, true, false, false);
        return Iterables.getOnlyElement(nativeQueries);
    }

    public Single<Integer> executeForRowCount(@NonNull Query query) {
        return execute(query)
                .map(PgResult::rowCount);
    }

    public Maybe<Row> executeForSingleRow(@NonNull Query query) {
        return execute(query)
                .flatMapMaybe(pgRowSet -> {
                    if (pgRowSet.size() > 1) {
                        return Maybe.error(new RuntimeException("Expected one result for query '" + query.getSQL() + "' but got " + pgRowSet.size()));
                    }

                    final PgIterator iterator = pgRowSet.iterator();
                    if (iterator.hasNext()) {
                        return Maybe.just(iterator.next());
                    } else {
                        return Maybe.empty();
                    }
                });
    }

    public Observable<Row> stream(@NonNull Query query, @NonNull Integer offset, @NonNull Integer limit) {
        try {
            final NativeQuery nativeQuery = parseNativeQuery(query);

            final List<Object> bindValues = retypeBindValues(query, offset, limit);

            // I've no idea how the rxStreams are supposed to work, because they leak connections
            // when you do it like in the docs ....
            return pgPool.rxPreparedQuery(nativeQuery.nativeSql, new Tuple(new ArrayTuple(bindValues)))
                    .flatMapObservable(pgRowSet -> Observable.fromIterable(pgRowSet.getDelegate()))
                    .map(Row::newInstance);

        } catch (SQLException e) {
            return Observable.error(e);
        }
    }

    /**
     * This is a hack, because jOOQ believes that offsets and limits are integers, whereas postgres and thus reactive-pg-client
     * believes them to be int8 aka Long.
     * <p>
     * I actually think reactive-pg-client should upcast that, but as I don't have a minimal test case now and before
     * somebody asks my why I use jOOQ with reactive-pg-client I wait till I publish this and then raise an issue
     */
    private List<Object> retypeBindValues(@NonNull Query query, @NonNull Integer offset, @NonNull Integer limit) {
        final List<Object> bindValues = new ArrayList<>(query.getBindValues());
        if (limit > 0) {
            final int lastElementIndex = bindValues.size() - 1;
            final Object lastBindValue = bindValues.get(lastElementIndex);
            if (limit.equals(lastBindValue)) {
                bindValues.set(lastElementIndex, (long) limit);
            } else {
                throw new IllegalStateException("Expecting limit to be last value in the bind values, but it is " + lastBindValue);
            }

            if (offset > 0) {
                final int secondLastElementIndex = lastElementIndex - 1;
                final Object secondLastBindValue = bindValues.get(secondLastElementIndex);
                if (offset.equals(secondLastBindValue)) {
                    bindValues.set(secondLastElementIndex, (long) offset);
                } else {
                    throw new IllegalThreadStateException("Expecting offset to be second last bind value, but it is " + secondLastBindValue);
                }
            }
        } else {
            if (offset > 0) {
                final int lastElementIndex = bindValues.size() - 1;
                final Object lastBindValue = bindValues.get(lastElementIndex);
                if (offset.equals(lastBindValue)) {
                    bindValues.set(lastElementIndex, (long) offset);
                } else {
                    throw new IllegalStateException("Expecting limit to be last value in the bind values, but it is " + lastBindValue);
                }
            }
        }
        return bindValues;
    }

    private Tuple bindValuesToTuple(Query query) {
        return new Tuple(new ArrayTuple(query.getBindValues()));
    }
}
