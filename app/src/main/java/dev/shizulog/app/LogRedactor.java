package dev.shizulog.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LogRedactor {

    private static final String REDACTED =
            "<REDACTED>";

    private static final Pattern AUTHORIZATION =
            Pattern.compile(
                    "(?i)(\\bAuthorization\\s*[:=]\\s*)"
                            + "([^\\r\\n]+)"
            );

    private static final Pattern COOKIE =
            Pattern.compile(
                    "(?i)(\\b(?:Set-Cookie|Cookie)\\s*:\\s*)"
                            + "([^\\r\\n]+)"
            );

    private static final Pattern BEARER =
            Pattern.compile(
                    "(?i)(\\bBearer\\s+)"
                            + "([A-Za-z0-9._~+/"
                            + "\\-=]{10,})"
            );

    private static final Pattern KEY_VALUE_SECRET =
            Pattern.compile(
                    "(?i)("
                            + "\\b(?:access_token"
                            + "|refresh_token"
                            + "|id_token"
                            + "|api[_-]?key"
                            + "|client[_-]?secret"
                            + "|session(?:id)?"
                            + "|auth[_-]?token"
                            + "|password"
                            + "|passwd"
                            + ")\\b"
                            + "\\s*[\"']?\\s*[:=]\\s*[\"']?"
                            + ")"
                            + "([^\\s\"'&,;}{]+)"
            );

    private static final Pattern QUERY_SECRET =
            Pattern.compile(
                    "(?i)([?&]"
                            + "(?:access_token"
                            + "|refresh_token"
                            + "|id_token"
                            + "|api[_-]?key"
                            + "|token"
                            + "|session(?:id)?"
                            + ")=)"
                            + "([^&#\\s]+)"
            );

    private static final Pattern JWT =
            Pattern.compile(
                    "\\beyJ[A-Za-z0-9_-]{8,}"
                            + "\\.[A-Za-z0-9_-]{8,}"
                            + "\\.[A-Za-z0-9_-]{8,}\\b"
            );

    private LogRedactor() {}

    public static String redactLine(
            String line
    ) {
        if (line == null
                || line.isEmpty()) {
            return line == null
                    ? ""
                    : line;
        }

        String value = line;

        value = replaceSecondGroup(
                AUTHORIZATION,
                value
        );

        value = replaceSecondGroup(
                COOKIE,
                value
        );

        value = replaceSecondGroup(
                BEARER,
                value
        );

        value = replaceSecondGroup(
                KEY_VALUE_SECRET,
                value
        );

        value = replaceSecondGroup(
                QUERY_SECRET,
                value
        );

        value = JWT.matcher(value)
                .replaceAll(REDACTED);

        return value;
    }

    private static String replaceSecondGroup(
            Pattern pattern,
            String input
    ) {
        Matcher matcher =
                pattern.matcher(input);

        StringBuffer out =
                new StringBuffer();

        while (matcher.find()) {
            String prefix =
                    matcher.group(1);

            matcher.appendReplacement(
                    out,
                    Matcher.quoteReplacement(
                            prefix + REDACTED
                    )
            );
        }

        matcher.appendTail(out);

        return out.toString();
    }
}
