package ai.core.cli.plugin;

final class VersionComparator {

    static int compareVersions(String v1, String v2) {
        if (v1 == null && v2 == null) return 0;
        if (v1 == null) return -1;
        if (v2 == null) return 1;
        if (v1.equals(v2)) return 0;

        var parts1 = parseVersionParts(v1);
        var parts2 = parseVersionParts(v2);

        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            int p1 = i < parts1.length ? parts1[i] : 0;
            int p2 = i < parts2.length ? parts2[i] : 0;
            if (p1 != p2) return Integer.compare(p1, p2);
        }

        boolean hasPreRelease1 = hasPreRelease(v1);
        boolean hasPreRelease2 = hasPreRelease(v2);
        if (hasPreRelease1 && hasPreRelease2) {
            return comparePreRelease(extractPreRelease(v1), extractPreRelease(v2));
        }
        if (hasPreRelease1 != hasPreRelease2) {
            return hasPreRelease1 ? -1 : 1;
        }
        return 0;
    }

    private static int[] parseVersionParts(String version) {
        int dashIdx = version.indexOf('-');
        String numericPart = dashIdx > 0 ? version.substring(0, dashIdx) : version;
        String[] parts = numericPart.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }

    private static String extractPreRelease(String version) {
        int dashIdx = version.indexOf('-');
        return dashIdx > 0 ? version.substring(dashIdx + 1) : "";
    }

    private static int comparePreRelease(String pr1, String pr2) {
        if (pr1.equals(pr2)) return 0;
        String[] parts1 = pr1.split("\\.");
        String[] parts2 = pr2.split("\\.");
        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            if (i >= parts1.length) return -1;
            if (i >= parts2.length) return 1;
            String p1 = parts1[i];
            String p2 = parts2[i];
            boolean isNum1 = isNumeric(p1);
            boolean isNum2 = isNumeric(p2);
            if (isNum1 && isNum2) {
                int cmp = Integer.compare(Integer.parseInt(p1), Integer.parseInt(p2));
                if (cmp != 0) return cmp;
            } else if (isNum1) {
                return -1;
            } else if (isNum2) {
                return 1;
            } else {
                int cmp = p1.compareTo(p2);
                if (cmp != 0) return cmp;
            }
        }
        return 0;
    }

    private static boolean hasPreRelease(String version) {
        return version != null && version.contains("-");
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (char c : s.toCharArray()) {
            if (!Character.isDigit(c)) return false;
        }
        return true;
    }

    private VersionComparator() {
    }
}
