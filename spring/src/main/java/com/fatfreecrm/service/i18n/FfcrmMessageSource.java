package com.fatfreecrm.service.i18n;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.NoSuchMessageException;
import org.springframework.stereotype.Component;

/**
 * AB-273 (settings-i18n): Spring {@link MessageSource} facade over {@link I18nService}, registered
 * under the bean name {@code messageSource}. CRITICAL: a missing code returns
 * {@code defaultMessage} or throws {@link NoSuchMessageException} per the MessageSource contract —
 * never the Rails-style "Translation missing" string — so ResponseEntityExceptionHandler problem
 * details and Bean Validation interpolation are unchanged.
 *
 * <p>Positional arguments are mapped to the names "0","1",…; a single {@link Map} argument is
 * treated as the named-argument map.
 */
@Component("messageSource")
public class FfcrmMessageSource implements MessageSource {

    private static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("en-US");

    private final I18nService i18nService;

    public FfcrmMessageSource(I18nService i18nService) {
        this.i18nService = i18nService;
    }

    @Override
    public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
        return i18nService.find(code, effective(locale))
            .map(pattern -> format(code, locale, args))
            .orElse(defaultMessage);
    }

    @Override
    public String getMessage(String code, Object[] args, Locale locale) {
        return i18nService.find(code, effective(locale))
            .map(pattern -> format(code, locale, args))
            .orElseThrow(() -> new NoSuchMessageException(code, locale));
    }

    @Override
    public String getMessage(MessageSourceResolvable resolvable, Locale locale) {
        String[] codes = resolvable.getCodes();
        if (codes != null) {
            for (String code : codes) {
                if (i18nService.find(code, effective(locale)).isPresent()) {
                    return format(code, locale, resolvable.getArguments());
                }
            }
        }
        String defaultMessage = resolvable.getDefaultMessage();
        if (defaultMessage != null) {
            return defaultMessage;
        }
        String code = codes != null && codes.length > 0 ? codes[codes.length - 1] : "";
        throw new NoSuchMessageException(code, locale);
    }

    private String format(String code, Locale locale, Object[] args) {
        return i18nService.t(code, effective(locale), namedArgs(args));
    }

    private static Locale effective(Locale locale) {
        // MessageSource declares locale @Nullable; FfcrmLocaleResolver falls back to en-US too.
        return locale == null ? DEFAULT_LOCALE : locale;
    }

    private static Map<String, Object> namedArgs(Object[] args) {
        Map<String, Object> named = new LinkedHashMap<>();
        if (args == null) {
            return named;
        }
        if (args.length == 1 && args[0] instanceof Map<?, ?> map) {
            map.forEach((key, value) -> named.put(String.valueOf(key), value));
            return named;
        }
        for (int i = 0; i < args.length; i++) {
            named.put(String.valueOf(i), args[i]);
        }
        return named;
    }
}
