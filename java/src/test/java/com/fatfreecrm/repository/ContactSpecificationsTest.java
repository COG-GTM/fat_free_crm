package com.fatfreecrm.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ContactSpecificationsTest {

    @Test
    void twoWordsGiveBothOrders() {
        assertThat(ContactSpecifications.namePermutations(new String[] {"Carol", "Clark"}))
                .containsExactly(new String[] {"Carol", "Clark"}, new String[] {"Clark", "Carol"});
    }

    @Test
    void singleWordHasNoPermutations() {
        assertThat(ContactSpecifications.namePermutations(new String[] {"Carol"})).isEmpty();
    }

    @Test
    void threeWordsGiveFourPermutations() {
        assertThat(ContactSpecifications.namePermutations(new String[] {"Mary", "Ann", "Smith"})).containsExactly(
                new String[] {"Mary", "Ann Smith"}, new String[] {"Ann Smith", "Mary"},
                new String[] {"Mary Ann", "Smith"}, new String[] {"Smith", "Mary Ann"});
    }

    /** Same vectors as spec/lib/core_ext/string_spec.rb for {@code String#name_permutations}. */
    @Test
    void matchesTheRailsStringSpecForStephanieManChiLo() {
        List<String[]> perms = ContactSpecifications.namePermutations("Stephanie Man Chi Lo".split(" "));

        assertThat(perms).hasSize(6);
        assertThat(perms).map(p -> p[0] + "|" + p[1]).containsExactlyInAnyOrder(
                "Stephanie|Man Chi Lo",
                "Stephanie Man|Chi Lo",
                "Stephanie Man Chi|Lo",
                "Lo|Stephanie Man Chi",
                "Chi Lo|Stephanie Man",
                "Man Chi Lo|Stephanie");
    }

    @Test
    void fourWordsGiveSixPermutationsLikeRails() {
        List<String[]> perms = ContactSpecifications.namePermutations(new String[] {"A", "B", "C", "D"});
        assertThat(perms).containsExactly(
                new String[] {"A", "B C D"}, new String[] {"B C D", "A"},
                new String[] {"A B", "C D"}, new String[] {"C D", "A B"},
                new String[] {"A B C", "D"}, new String[] {"D", "A B C"});
    }
}
