package com.fatfreecrm.service.i18n;

/**
 * AB-273 (settings-i18n): thrown when a translation pattern references an interpolation argument
 * that was not supplied, mirroring Rails' {@code I18n::MissingInterpolationArgument}.
 */
public class MissingInterpolationArgumentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MissingInterpolationArgumentException(String key, String argument) {
        super("missing interpolation argument :" + argument + " in \"" + key + "\"");
    }
}
