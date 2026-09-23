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
}
