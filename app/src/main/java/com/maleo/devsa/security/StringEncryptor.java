package com.maleo.devsa.security;

/**
 * StringEncryptor — Same Hex+Octal algorithm.
 * Used to generate obfuscated auth directory names from activation keys.
 */
public final class StringEncryptor {

    private StringEncryptor() {
    }

    public static String encrypt(String input) {
        if (input == null || input.isEmpty()) return "";
        int half = input.length() / 2;
        String firstHalf = input.substring(0, half);
        String secondHalf = input.substring(half);
        StringBuilder sb = new StringBuilder();
        for (char c : firstHalf.toCharArray()) sb.append(String.format("%02x", (int) c));
        for (char c : secondHalf.toCharArray()) sb.append(Integer.toOctalString(c));
        sb.append(String.format("%02x", half));
        return sb.toString();
    }

    public static String decrypt(String encrypted) {
        if (encrypted == null || encrypted.length() < 2) return "";
        try {
            int half = Integer.parseInt(encrypted.substring(encrypted.length() - 2), 16);
            String hex = encrypted.substring(0, half * 2);
            String oct = encrypted.substring(half * 2, encrypted.length() - 2);
            StringBuilder first = new StringBuilder();
            for (int i = 0; i < hex.length(); i += 2)
                first.append((char) Integer.parseInt(hex.substring(i, i + 2), 16));
            StringBuilder second = new StringBuilder();
            int idx = 0;
            while (idx < oct.length()) {
                if (idx + 3 <= oct.length()) {
                    try {
                        second.append((char) Integer.parseInt(oct.substring(idx, idx + 3), 8));
                        idx += 3;
                        continue;
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (idx + 2 <= oct.length()) {
                    second.append((char) Integer.parseInt(oct.substring(idx, idx + 2), 8));
                    idx += 2;
                } else break;
            }
            return first + second.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
