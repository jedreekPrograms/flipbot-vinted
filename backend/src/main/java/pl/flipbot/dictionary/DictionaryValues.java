package pl.flipbot.dictionary;

import java.util.Arrays;
import java.util.List;
import pl.flipbot.exception.InvalidRequestException;

final class DictionaryValues {

    private DictionaryValues() {}

    static String normalizeName(String value, String label) {
        if (value == null) {
            throw new InvalidRequestException(label + " name cannot be null");
        }
        String normalized = value
                .trim()
                .replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            throw new InvalidRequestException(label + " name cannot be blank");
        }
        return normalized;
    }

    static List<String> normalizeCategoryPath(List<String> categoryPath) {
        if (categoryPath == null || categoryPath.isEmpty()) {
            throw new InvalidRequestException("Category path cannot be empty");
        }
        return categoryPath.stream()
                .map(element -> normalizeName(element, "Category path element"))
                .peek(element -> {
                    if (element.contains(">")) {
                        throw new InvalidRequestException("Category path element cannot contain the '>' character: "
                                        + element);
                    }
                })
                .toList();
    }

    static List<String> splitPath(String path) {
        return Arrays.stream(path.split("\\s*>\\s*"))
                .map(String::trim)
                .filter(element -> !element.isBlank())
                .toList();
    }

    static boolean sameText(String left, String right) {
        return left != null
                && right != null
                && left.trim().equalsIgnoreCase(right.trim());
    }

    static boolean samePath(List<String> left, List<String> right) {
        if (left == null
                || right == null
                || left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!sameText(left.get(index), right.get(index))) {
                return false;
            }
        }
        return true;
    }
}
