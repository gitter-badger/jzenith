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
package org.jzenith.rest.exception;

import com.google.common.base.Throwables;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.jzenith.rest.model.ErrorResponse;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;

@Slf4j
@Data
@AllArgsConstructor
public class ExceptionMapping<T extends Exception> {

    @NonNull
    private final Class<T> exceptionType;

    private final int statusCode;

    public Response toResponse(T exception) {
        final Throwable rootCause = Throwables.getRootCause(exception);
        if (rootCause instanceof IllegalArgumentException) {
            return makeResponse(rootCause, Response.Status.BAD_REQUEST.getStatusCode());
        }

        if (exception instanceof WebApplicationException) {
            final WebApplicationException webApplicationException = (WebApplicationException) exception;

            return makeResponse(webApplicationException, webApplicationException.getResponse().getStatus());
        }

        return makeResponse(exception, statusCode);
    }

    private Response makeResponse(Throwable exception, int statusCode) {
        log.debug("Sending {} for exception", statusCode, exception);
        return Response.status(statusCode)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse(statusCode, exception.getMessage()))
                .build();
    }

    public ExceptionMapper toExceptionHandler() {
        return exception -> toResponse(exceptionType.cast(exception));
    }
}
