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
    void fourWordsGiveSixPermutationsLikeRails() {
        List<String[]> perms = ContactSpecifications.namePermutations(new String[] {"A", "B", "C", "D"});
        assertThat(perms).containsExactly(
                new String[] {"A", "B C D"}, new String[] {"B C D", "A"},
                new String[] {"A B", "C D"}, new String[] {"C D", "A B"},
                new String[] {"A B C", "D"}, new String[] {"D", "A B C"});
    }

    @Test
    void threeWordsGiveTheFourSplitsInRailsOrder() {
        // "A B C".name_permutations => [["A", "B C"], ["B C", "A"], ["A B", "C"], ["C", "A B"]]
        assertThat(ContactSpecifications.namePermutations(new String[] {"A", "B", "C"})).containsExactly(
                new String[] {"A", "B C"}, new String[] {"B C", "A"},
                new String[] {"A B", "C"}, new String[] {"C", "A B"});
    }

    @Test
    void nWordsGiveTwoTimesNMinusOnePairs() {
        // spec/lib/core_ext/string_spec.rb: "Stephanie Man Chi Lo" => 6 pairs
        List<String[]> perms = ContactSpecifications.namePermutations(new String[] {"Stephanie", "Man", "Chi", "Lo"});
        assertThat(perms).hasSize(6);
        assertThat(perms).extracting(p -> p[0] + "|" + p[1]).containsExactlyInAnyOrder(
                "Stephanie|Man Chi Lo", "Man Chi Lo|Stephanie",
                "Stephanie Man|Chi Lo", "Chi Lo|Stephanie Man",
                "Stephanie Man Chi|Lo", "Lo|Stephanie Man Chi");
    }

    @Test
    void singleWordHasNoPermutations() {
        // Rails: "Carol".name_permutations => [] (text_search takes the first-or-last branch instead)
        assertThat(ContactSpecifications.namePermutations(new String[] {"Carol"})).isEmpty();
    }

    @Test
    void permutationsPreserveTheOriginalWordSpelling() {
        List<String[]> perms = ContactSpecifications.namePermutations(new String[] {"O'Connell", "Shamus"});
        assertThat(perms).containsExactly(
                new String[] {"O'Connell", "Shamus"}, new String[] {"Shamus", "O'Connell"});
    }
}
