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
    void threeWordsGiveFourPermutationsInRailsOrder() {
        // Rails "A B C".name_permutations => [["A","B C"],["B C","A"],["A B","C"],["C","A B"]]
        assertThat(ContactSpecifications.namePermutations(new String[] {"A", "B", "C"})).containsExactly(
                new String[] {"A", "B C"}, new String[] {"B C", "A"},
                new String[] {"A B", "C"}, new String[] {"C", "A B"});
    }

    @Test
    void singleWordGivesNoPermutations() {
        assertThat(ContactSpecifications.namePermutations(new String[] {"Alice"})).isEmpty();
    }

    @Test
    void permutationCountIsTwiceWordsMinusOne() {
        for (int n = 2; n <= 6; n++) {
            String[] words = new String[n];
            for (int i = 0; i < n; i++) {
                words[i] = "w" + i;
            }
            assertThat(ContactSpecifications.namePermutations(words)).as(n + " words").hasSize(2 * (n - 1));
        }
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
