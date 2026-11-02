package pl.flipbot.playwright.filters.catalog;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CatalogUrl {
    private CatalogUrl() {}

    public static String parameter(String currentUrl, String parameterName) {
        int questionMarkIndex = currentUrl.indexOf('?');

        if (questionMarkIndex < 0 || questionMarkIndex == currentUrl.length() - 1) {
            return null;
        }

        String query = currentUrl.substring(questionMarkIndex + 1);
        int fragmentIndex = query.indexOf('#');
        if (fragmentIndex >= 0) {
            query = query.substring(0, fragmentIndex);
        }

        for (String parameter : query.split("&")) {
            int equalsIndex = parameter.indexOf('=');
            String rawName = equalsIndex >= 0
                    ? parameter.substring(0, equalsIndex)
                    : parameter;
            String rawValue = equalsIndex >= 0
                    ? parameter.substring(equalsIndex + 1)
                    : "";
            String decodedName = URLDecoder.decode(rawName, StandardCharsets.UTF_8);

            if (parameterName.equals(decodedName)) {
                return URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            }
        }

        return null;
    }

    public static String withParameter(String url, String parameterName, String parameterValue) {
        String fragment = "";

        String withoutFragment = url;

        int fragmentIndex = url.indexOf('#');

        if (fragmentIndex >= 0) {
            fragment = url.substring(fragmentIndex);

            withoutFragment = url.substring(0, fragmentIndex);
        }

        String encodedParameterName = Pattern.quote(parameterName);

        Pattern pattern = Pattern.compile("([?&])" + encodedParameterName + "=[^&#]*");

        Matcher matcher = pattern.matcher(withoutFragment);

        if (matcher.find()) {
            return matcher.replaceFirst("$1" + parameterName + "=" + parameterValue)
                    + fragment;
        }

        String separator = withoutFragment.contains("?") ? "&" : "?";

        return withoutFragment
                + separator
                + parameterName
                + "="
                + parameterValue
                + fragment;
    }
}
