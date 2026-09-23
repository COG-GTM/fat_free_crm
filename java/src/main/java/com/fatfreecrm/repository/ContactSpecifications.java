package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Contact;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/**
 * JPA Specifications for {@link Contact} queries, mirroring the Rails scopes in
 * app/models/entities/contact.rb.
 */
public final class ContactSpecifications {

    private ContactSpecifications() {
    }

    public static Specification<Contact> idEquals(long id) {
        return (root, query, cb) -> cb.equal(root.get("id"), id);
    }

    /**
     * Rails {@code Contact.text_search(query)}: case-insensitive {@code LIKE %query%} matching
     * (Arel {@code matches} → PostgreSQL {@code ILIKE}; {@code %}/{@code _} in the query are not
     * escaped, as in Rails).
     *
     * <ul>
     *   <li>Query without a space: {@code first_name ILIKE %q% OR last_name ILIKE %q%}.</li>
     *   <li>Query with spaces: every first/last split of the words, in both orders
     *       ({@code String#name_permutations} in lib/fat_free_crm/core_ext/string.rb — "A B C"
     *       gives ("A","B C"), ("B C","A"), ("A B","C"), ("C","A B")), each matched as
     *       {@code first_name ILIKE %first% AND last_name ILIKE %last%} and OR-ed together.</li>
     *   <li>Always OR-ed with {@code email}, {@code alt_email}, {@code phone}, {@code mobile}
     *       {@code ILIKE %q%} on the whole query.</li>
     * </ul>
     * A blank query yields an always-true predicate.
     */
    public static Specification<Contact> textSearch(String query) {
        if (query == null || query.isBlank()) {
            return (root, q, cb) -> cb.conjunction();
        }
        String trimmed = query.strip();
        return (root, q, cb) -> {
            List<Predicate> alternatives = new ArrayList<>();
            String[] words = trimmed.split("\\s+");
            if (words.length == 1) {
                alternatives.add(contains(cb, root.get("firstName"), trimmed));
                alternatives.add(contains(cb, root.get("lastName"), trimmed));
            } else {
                for (String[] permutation : namePermutations(words)) {
                    alternatives.add(cb.and(
                            contains(cb, root.get("firstName"), permutation[0]),
                            contains(cb, root.get("lastName"), permutation[1])));
                }
            }
            alternatives.add(contains(cb, root.get("email"), trimmed));
            alternatives.add(contains(cb, root.get("altEmail"), trimmed));
            alternatives.add(contains(cb, root.get("phone"), trimmed));
            alternatives.add(contains(cb, root.get("mobile"), trimmed));
            return cb.or(alternatives.toArray(Predicate[]::new));
        };
    }

    /** All (first, last) splits of the words, each also reversed; {@code n} words → {@code 2(n-1)} pairs. */
    static List<String[]> namePermutations(String[] words) {
        List<String[]> permutations = new ArrayList<>();
        for (int i = 1; i < words.length; i++) {
            String first = String.join(" ", Arrays.copyOfRange(words, 0, i));
            String last = String.join(" ", Arrays.copyOfRange(words, i, words.length));
            permutations.add(new String[] {first, last});
            permutations.add(new String[] {last, first});
        }
        return permutations;
    }

    private static Predicate contains(CriteriaBuilder cb, Path<String> column, String needle) {
        return cb.like(cb.lower(column), "%" + needle.toLowerCase(Locale.ROOT) + "%");
    }
}
