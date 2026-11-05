package net.talaatharb.imagegen.service;

/** Raised when a model cannot be loaded or inference fails. */
public class ImageServiceException extends RuntimeException {
    public ImageServiceException(String message) {
        super(message);
    }

    public ImageServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
