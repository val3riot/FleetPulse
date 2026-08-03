package it.fleetpulse.api.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final Clock clock;
    private final DatabaseConstraintErrorResolver constraintResolver;
    private final DatabaseAvailabilityClassifier availabilityClassifier;

    /**
     * Crea il gestore con clock e classificatori degli errori database.
     */
    public GlobalExceptionHandler(
            Clock clock,
            DatabaseConstraintErrorResolver constraintResolver,
            DatabaseAvailabilityClassifier availabilityClassifier
    ) {
        this.clock = clock;
        this.constraintResolver = constraintResolver;
        this.availabilityClassifier = availabilityClassifier;
    }

    /*
     * Eccezioni applicative controllate:
     * not found, conflict, transizione di stato non valida, ecc.
     */
    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<Object> handleApplicationException(
            ApplicationException exception,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = exception.getErrorCode();
        log.debug(
                "Application error {} while processing {} {}",
                errorCode.getCode(),
                request.getMethod(),
                request.getRequestURI()
        );
        return buildResponse(
                errorCode,
                publicMessage(exception, errorCode),
                request.getRequestURI(),
                List.of(),
                HttpHeaders.EMPTY
        );
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        List<ValidationErrorDetail> details = Stream.concat(
                        exception.getBindingResult()
                                .getFieldErrors()
                                .stream()
                                .map(error -> new ValidationErrorDetail(
                                        error.getField(),
                                        Objects.requireNonNullElse(
                                                error.getDefaultMessage(),
                                                "invalid value"
                                        )
                                )),
                        exception.getBindingResult()
                                .getGlobalErrors()
                                .stream()
                                .map(error -> new ValidationErrorDetail(
                                        "request",
                                        Objects.requireNonNullElse(
                                                error.getDefaultMessage(),
                                                "invalid request"
                                        )
                                ))
                )
                .toList();
        log.debug(
                "Request validation failed for {}: {} error(s)",
                path(request),
                details.size()

        );

        ErrorCode errorCode = ErrorCode.REQUEST_INVALID;

        return buildResponse(
                errorCode,
                errorCode.getDefaultMessage(),
                path(request),
                details,
                headers
        );
    }
    /*
    *
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        ErrorCode errorCode = ErrorCode.REQUEST_UNSUPPORTED_MEDIA_TYPE;
        log.debug(
                "Media type {} non supported for {}",
                exception.getContentType(),
                path(request)
        );
        return buildResponse(
                errorCode,
                errorCode.getDefaultMessage(),
                path(request),
                List.of(),
                headers
        );
    }
    /*
    *
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ErrorCode errorCode = ErrorCode.REQUEST_METHOD_NOT_ALLOWED;
        log.debug(
                "HTTP method {} not supported for {}",
                exception.getMethod(),
                path(request)
        );

        return buildResponse(
                errorCode,
                errorCode.getDefaultMessage(),
                path(request),
                List.of(),
                headers
        );
    }

    /*
     * JSON invalido, body mancante, enum non deserializzabile,
     * valore numerico incompatibile, ecc.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        log.debug(
                "Unreadable request body for {}",
                path(request)
        );

        return buildResponse(
                ErrorCode.REQUEST_MALFORMED_JSON,
                ErrorCode.REQUEST_MALFORMED_JSON.getDefaultMessage(),
                path(request),
                List.of(),
                headers
        );
    }
    /*
     * Mismatch su path variable
     */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        String field = Optional.ofNullable(exception.getPropertyName())
                .orElse("parameter");

        ValidationErrorDetail detail = new ValidationErrorDetail(
                field,
                "has an invalid value"
        );

        log.debug(
                "Type mismatch for {} on {}",
                field,
                path(request)
        );

        return buildResponse(
                ErrorCode.REQUEST_INVALID,
                ErrorCode.REQUEST_INVALID.getDefaultMessage(),
                path(request),
                List.of(detail),
                headers
        );
    }

    /**
     * Converte le violazioni dei vincoli sui parametri in una richiesta non valida.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        List<ValidationErrorDetail> details = exception.getConstraintViolations()
                .stream()
                .map(this::toValidationDetail)
                .toList();

        return buildResponse(
                ErrorCode.REQUEST_INVALID,
                ErrorCode.REQUEST_INVALID.getDefaultMessage(),
                request.getRequestURI(),
                details,
                HttpHeaders.EMPTY
        );
    }

    /**
     * Converte un query parameter obbligatorio assente in una richiesta non valida.
     */
    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        return invalidParameterResponse(
                exception.getParameterName(),
                path(request),
                headers
        );
    }

    /**
     * Converte una path variable obbligatoria assente in una richiesta non valida.
     */
    @Override
    protected ResponseEntity<Object> handleMissingPathVariable(
            MissingPathVariableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        return invalidParameterResponse(
                exception.getVariableName(),
                path(request),
                headers
        );
    }

    /*
     * Vincoli univoci realmente rilevati dal database.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrityViolation(
            DataIntegrityViolationException exception,
            HttpServletRequest request
    ) {
        Optional<ErrorCode> resolved =
                constraintResolver.resolve(exception);

        if (resolved.isPresent()) {
            ErrorCode errorCode = resolved.get();

            log.debug(
                    "Database constraint conflict {} while processing {} {}",
                    errorCode.getCode(),
                    request.getMethod(),
                    request.getRequestURI()
            );

            return buildResponse(
                    errorCode,
                    errorCode.getDefaultMessage(),
                    request.getRequestURI(),
                    List.of(),
                    HttpHeaders.EMPTY
            );
        }

        /*
         * Una violazione sconosciuta non deve diventare automaticamente
         * un generico 409: potrebbe indicare un bug applicativo.
         */
        log.error(
                "Unhandled database integrity violation while processing {} {}",
                request.getMethod(),
                request.getRequestURI(),
                exception
        );

        return buildResponse(
                ErrorCode.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.getDefaultMessage(),
                request.getRequestURI(),
                List.of(),
                HttpHeaders.EMPTY
        );
    }


    @ExceptionHandler({DataAccessException.class})
    public ResponseEntity<Object> handleDataAccessException(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        if (availabilityClassifier.isConnectionFailure(exception)) {
            log.warn(
                    "Database connection failure while processing {} {}",
                    request.getMethod(),
                    request.getRequestURI(),
                    exception
            );

            return buildResponse(
                    ErrorCode.SERVICE_UNAVAILABLE,
                    ErrorCode.SERVICE_UNAVAILABLE.getDefaultMessage(),
                    request.getRequestURI(),
                    List.of(),
                    HttpHeaders.EMPTY
            );
        }

        log.error(
                "Unhandled database error while processing {} {}",
                request.getMethod(),
                request.getRequestURI(),
                exception
        );

        return buildResponse(
                ErrorCode.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.getDefaultMessage(),
                request.getRequestURI(),
                List.of(),
                HttpHeaders.EMPTY
        );
    }

    /*
     * Database non raggiungibile o transazione non avviabile.
     */
    @ExceptionHandler({
            CannotCreateTransactionException.class,
            DataAccessResourceFailureException.class
    })
    public ResponseEntity<Object> handleDatabaseUnavailable(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        log.warn(
                "Database unavailable while processing {} {}",
                request.getMethod(),
                request.getRequestURI(),
                exception
        );

        return buildResponse(
                ErrorCode.SERVICE_UNAVAILABLE,
                ErrorCode.SERVICE_UNAVAILABLE.getDefaultMessage(),
                request.getRequestURI(),
                List.of(),
                HttpHeaders.EMPTY
        );
    }

    /*
     * Errore generico
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error(
                "Unexpected error while processing {} {}",
                request.getMethod(),
                request.getRequestURI(),
                exception
        );

        return buildResponse(
                ErrorCode.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.getDefaultMessage(),
                request.getRequestURI(),
                List.of(),
                HttpHeaders.EMPTY
        );
    }


    /**
     * Costruisce la risposta REST uniforme associata a un codice applicativo.
     */
    private ResponseEntity<Object> buildResponse(
            ErrorCode errorCode,
            String message,
            String path,
            List<ValidationErrorDetail> details,
            HttpHeaders headers
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                Instant.now(clock),
                errorCode.getHttpStatus().value(),
                errorCode.getCode(),
                message,
                path,
                List.copyOf(details)
        );

        return new ResponseEntity<>(
                response,
                headers,
                errorCode.getHttpStatus()
        );
    }

    /**
     * Seleziona il messaggio pubblico dell'errore applicativo.
     */
    private String publicMessage(
            ApplicationException exception,
            ErrorCode errorCode
    ) {
        return exception.getMessage() == null
                ? errorCode.getDefaultMessage()
                : exception.getMessage();
    }

    /**
     * Estrae il path HTTP dalla richiesta web corrente.
     */
    private String path(WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest) {
            return servletWebRequest
                    .getRequest()
                    .getRequestURI();
        }

        return "";
    }

    /**
     * Costruisce una risposta di parametro non valido con dettaglio field-level.
     */
    private ResponseEntity<Object> invalidParameterResponse(
            String field,
            String path,
            HttpHeaders headers
    ) {
        return buildResponse(
                ErrorCode.REQUEST_INVALID,
                ErrorCode.REQUEST_INVALID.getDefaultMessage(),
                path,
                List.of(new ValidationErrorDetail(field, "is required")),
                headers
        );
    }

    /**
     * Converte una constraint violation nel dettaglio pubblico corrispondente.
     */
    private ValidationErrorDetail toValidationDetail(ConstraintViolation<?> violation) {
        String propertyPath = violation.getPropertyPath().toString();
        int separator = propertyPath.lastIndexOf('.');
        String field = propertyPath.isBlank()
                ? "request"
                : propertyPath.substring(separator + 1);

        return new ValidationErrorDetail(
                field,
                Objects.requireNonNullElse(violation.getMessage(), "invalid value")
        );
    }

}
