package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Account;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/** Query fragments for {@code accounts}, composed with {@code AccessControlSpecifications.visibleTo}. */
public final class AccountSpecifications {

    private static final char ESCAPE = '\\';

    private AccountSpecifications() {
    }

    /**
     * Rails {@code Account.text_search(query)} = {@code ransack(name_or_email_cont: query)}:
     * case-insensitive substring match on {@code name} or {@code email}. Ransack escapes SQL
     * wildcards in the user's input, so {@code %}, {@code _} and {@code \} are matched literally
     * here too. A blank query matches every row (Ransack ignores blank predicates).
     */
    public static Specification<Account> textSearch(String query) {
        if (query == null || query.isBlank()) {
            return (root, cq, cb) -> cb.conjunction();
        }
        String pattern = "%" + escapeWildcards(query.toLowerCase(Locale.ROOT)) + "%";
        return (root, cq, cb) -> cb.or(
                containsIgnoreCase(cb, root.get("name"), pattern),
                containsIgnoreCase(cb, root.get("email"), pattern));
    }

    public static Specification<Account> idEquals(long id) {
        return (root, cq, cb) -> cb.equal(root.get("id"), id);
    }

    private static Predicate containsIgnoreCase(CriteriaBuilder cb, Expression<String> column, String pattern) {
        return cb.like(cb.lower(column), pattern, ESCAPE);
    }

    private static String escapeWildcards(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (c == '%' || c == '_' || c == ESCAPE) {
                sb.append(ESCAPE);
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
