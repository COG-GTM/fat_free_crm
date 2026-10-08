package com.fatfreecrm.service.mailprocessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EmailReplyParser {

    private static final Pattern MULTILINE_HEADER = Pattern.compile(
        "^(On(.+)wrote:)$", Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern UNDERSCORE_LINE = Pattern.compile(
        "([^\\n])(?=\\n_{7}_+$)", Pattern.DOTALL | Pattern.MULTILINE);
    private static final Pattern SIGNATURE = Pattern.compile(
        "(--|__|\\w-$)|(^((?:\\w+\\s*){1,3}) ym morf tneS$)");
    private static final Pattern QUOTED = Pattern.compile("(>+)$");
    private static final Pattern QUOTE_HEADER = Pattern.compile("^:etorw.*nO$");

    private EmailReplyParser() {
    }

    public static ParsedEmail read(String source) {
        String text = source == null ? "" : source;
        if (text.isEmpty()) {
            return new ParsedEmail(List.of());
        }
        Matcher header = MULTILINE_HEADER.matcher(text);
        if (header.find()) {
            String matched = header.group(1);
            text = text.substring(0, header.start(1)) + matched.replace("\n", " ") + text.substring(header.end(1));
        }
        Matcher underscore = UNDERSCORE_LINE.matcher(text);
        StringBuffer separated = new StringBuffer();
        while (underscore.find()) {
            underscore.appendReplacement(separated, Matcher.quoteReplacement(underscore.group(1) + "\n"));
        }
        underscore.appendTail(separated);

        List<Fragment> fragments = new ArrayList<>();
        MutableFragment current = null;
        boolean foundVisible = false;
        String reversed = new StringBuilder(separated.toString()).reverse().toString();
        String[] lines = reversed.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (index == lines.length - 1 && line.isEmpty() && reversed.endsWith("\n")) {
                continue;
            }
            if (!SIGNATURE.matcher(line).find()) {
                line = stripLeading(line);
            }
            boolean quoted = QUOTED.matcher(line).find();
            if (current != null && line.isEmpty()) {
                String previous = current.lines.get(current.lines.size() - 1);
                if (SIGNATURE.matcher(previous).find()) {
                    current.signature = true;
                    Fragment complete = finish(current, foundVisible);
                    fragments.add(complete);
                    if (!complete.hidden()) {
                        foundVisible = true;
                    }
                    current = null;
                }
            }
            if (current != null && (current.quoted == quoted
                || current.quoted && (QUOTE_HEADER.matcher(line).find() || line.isEmpty()))) {
                current.lines.add(line);
            } else {
                if (current != null) {
                    Fragment complete = finish(current, foundVisible);
                    fragments.add(complete);
                    if (!complete.hidden()) {
                        foundVisible = true;
                    }
                }
                current = new MutableFragment(quoted, line);
            }
        }
        if (current != null) {
            Fragment complete = finish(current, foundVisible);
            fragments.add(complete);
            if (!complete.hidden()) {
                foundVisible = true;
            }
        }

        Collections.reverse(fragments);
        return new ParsedEmail(fragments);
    }

    public static String parseReply(String source) {
        return read(source).visibleText();
    }

    private static Fragment finish(MutableFragment fragment, boolean foundVisible) {
        String reversedContent = String.join("\n", fragment.lines);
        String content = new StringBuilder(reversedContent).reverse().toString();
        boolean hidden = !foundVisible
            && (fragment.quoted || fragment.signature || content.strip().isEmpty());
        return new Fragment(fragment.quoted, fragment.signature, hidden, content);
    }

    private static String stripLeading(String line) {
        int index = 0;
        while (index < line.length() && Character.isWhitespace(line.charAt(index))) {
            index++;
        }
        return line.substring(index);
    }

    public record Fragment(boolean quoted, boolean signature, boolean hidden, String content) {
    }

    public static final class ParsedEmail {

        private final List<Fragment> fragments;

        private ParsedEmail(List<Fragment> fragments) {
            this.fragments = List.copyOf(fragments);
        }

        public List<Fragment> fragments() {
            return fragments;
        }

        public String visibleText() {
            return fragments.stream().filter(fragment -> !fragment.hidden())
                .map(Fragment::content).reduce((left, right) -> left + "\n" + right).orElse("").stripTrailing();
        }
    }

    private static final class MutableFragment {

        private final boolean quoted;
        private final List<String> lines = new ArrayList<>();
        private boolean signature;

        private MutableFragment(boolean quoted, String firstLine) {
            this.quoted = quoted;
            lines.add(firstLine);
        }
    }
}
