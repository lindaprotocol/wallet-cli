package org.linda.core.exception;

public class LindaException extends Exception {

  public LindaException() {
    super();
  }

  public LindaException(String message) {
    super(message);
  }

  public LindaException(String message, Throwable cause) {
    super(message, cause);
  }

}
