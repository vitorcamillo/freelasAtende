package com.freelasatende.exception;

/**
 * Exceção para falhas de scraping e autenticação no 99Freelas.
 */
public class ScraperException extends FreelasException {

    public ScraperException(String message) {
        super("SCRAPER_ERROR", message);
    }

    public ScraperException(String message, Throwable cause) {
        super("SCRAPER_ERROR", message, cause);
    }
}
