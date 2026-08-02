package it.fleetpulse.api.common;

public class ApplicationException extends RuntimeException {
    private final ErrorCode errorCode;

    /**
     * Crea un'eccezione con il messaggio predefinito del codice applicativo.
     */
    public ApplicationException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    /**
     * Crea un'eccezione con un messaggio applicativo specifico.
     */
    public ApplicationException(
            ErrorCode errorCode,
            String message
    ) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * Crea un'eccezione applicativa preservandone la causa.
     */
    public ApplicationException(
            ErrorCode errorCode,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * Restituisce il codice di errore associato all'eccezione.
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
