package com.jihajoor.auth.emailauth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * The rule for every new password: sign-up, reset, change and staff invitations. Sign-in never
 * applies it, so a password set under an older rule keeps working.
 *
 * <ul>
 *   <li>10 to 72 UTF-8 bytes (bcrypt's limit), with at least one letter and one digit;</li>
 *   <li>not a well-known password once its digits and symbols are set aside ("Password@123",
 *       "qwerty12345", "india@2024");</li>
 *   <li>not a run of one character or a keyboard/number sequence;</li>
 *   <li>not built on the person's own email name or username.</li>
 * </ul>
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_BYTES = 72;

    /**
     * Words that, with digits and symbols added, make up most guessed passwords (from the public
     * most-common-password lists), plus local favourites.
     */
    private static final Set<String> COMMON_WORDS = Set.of(
            "password", "passw", "passwd", "pass", "qwerty", "qwertyuiop", "asdf", "asdfgh", "asdfghjkl",
            "zxcvbn", "zxcvbnm", "abc", "abcd", "abcde", "abcdef", "admin", "administrator", "welcome",
            "letmein", "iloveyou", "love", "monkey", "dragon", "master", "login", "hello", "secret",
            "sunshine", "princess", "football", "cricket", "baseball", "superman", "batman", "trustno",
            "india", "bharat", "hindustan", "mumbai", "delhi", "krishna", "ganesh", "shiva", "sairam",
            "omsairam", "jaishreeram", "user", "test", "guest", "default", "changeme", "computer",
            "internet", "samsung", "apple", "google", "facebook", "whatsapp", "qazwsx", "aaaaaa");

    private static final String[] SEQUENCES = {
            "0123456789", "1234567890", "9876543210", "0987654321",
            "abcdefghijklmnopqrstuvwxyz", "zyxwvutsrqponmlkjihgfedcba",
            "qwertyuiop", "asdfghjkl", "zxcvbnm", "1qaz2wsx3edc"};

    private PasswordPolicy() {
    }

    public static void requireAcceptable(String password) {
        requireAcceptable(password, (String[]) null);
    }

    /**
     * @param personal the account's email address, username or name, if known; a password built
     *                 on any of them is refused
     */
    public static void requireAcceptable(String password, String... personal) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw EmailAuthException.weakPassword("Use at least " + MIN_LENGTH + " characters.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw EmailAuthException.weakPassword("Use at most " + MAX_BYTES + " characters.");
        }
        boolean letter = password.codePoints().anyMatch(Character::isLetter);
        boolean digit = password.codePoints().anyMatch(Character::isDigit);
        if (!letter || !digit) {
            throw EmailAuthException.weakPassword("Use at least one letter and one number.");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        String letters = lower.replaceAll("[^\\p{L}]", "");
        String digits = lower.replaceAll("[^0-9]", "");
        // The word part once a leading or trailing run of digits and symbols ("@2024", "123!") is
        // set aside: "p@ssw0rd2024" is "p@ssw0rd", which reads "password".
        String core = lower.replaceAll("^[^\\p{L}]+|[^\\p{L}]+$", "");
        if (COMMON_WORDS.contains(letters) || COMMON_WORDS.contains(leetToLetters(lower))
                || COMMON_WORDS.contains(leetToLetters(core))) {
            throw EmailAuthException.weakPassword("That password is too common. Choose something less predictable.");
        }
        if (lower.chars().distinct().count() <= 3 || isSequence(lower)
                || (letters.length() >= 6 && inSequence(letters) && digits.length() <= 4)
                || (digits.length() >= 6 && inSequence(digits) && letters.length() <= 2)) {
            throw EmailAuthException.weakPassword("Avoid repeated characters and simple sequences.");
        }
        if (personal != null) {
            for (String value : personal) {
                String base = personalBase(value);
                if (base != null && lower.contains(base)) {
                    throw EmailAuthException.weakPassword("Don't use your name or email address in your password.");
                }
            }
        }
    }

    /** The letters of a password once common character swaps (@ for a, 0 for o...) are undone. */
    private static String leetToLetters(String lower) {
        return lower.replace('@', 'a').replace('4', 'a').replace('0', 'o').replace('1', 'i')
                .replace('3', 'e').replace('$', 's').replace('5', 's').replace('7', 't')
                .replaceAll("[^\\p{L}]", "");
    }

    /** True when the password, without its symbols, sits inside one long keyboard or number run. */
    private static boolean isSequence(String lower) {
        String compact = lower.replaceAll("[^\\p{L}\\p{Nd}]", "");
        if (compact.length() < MIN_LENGTH - 2) {
            return false;
        }
        return inSequence(compact);
    }

    /** True when {@code part} sits inside one keyboard, alphabet or number run. */
    private static boolean inSequence(String part) {
        for (String sequence : SEQUENCES) {
            if (sequence.contains(part)) {
                return true;
            }
        }
        return false;
    }

    /** The part of an email (before @) or a name worth refusing, or null when too short to matter. */
    private static String personalBase(String value) {
        if (value == null) {
            return null;
        }
        String base = value.toLowerCase(Locale.ROOT).strip();
        int at = base.indexOf('@');
        if (at >= 0) {
            base = base.substring(0, at);
        }
        base = base.replaceAll("[^\\p{L}\\p{Nd}]", "");
        return base.length() >= 4 ? base : null;
    }
}
