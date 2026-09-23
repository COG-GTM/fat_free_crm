package com.fatfreecrm.api.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.api.dto.AutoCompleteResponse.Item;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AutoCompleteResponseTest {

    @Test
    void nullResultsBecomeEmptyList() {
        assertThat(new AutoCompleteResponse(null).results()).isEmpty();
    }

    @Test
    void resultsAreCopiedAndImmutable() {
        List<Item> source = new ArrayList<>(List.of(new Item(1, "Acme")));
        AutoCompleteResponse response = new AutoCompleteResponse(source);
        source.add(new Item(2, "Bravo"));

        assertThat(response.results()).containsExactly(new Item(1, "Acme"));
        assertThatThrownBy(() -> response.results().add(new Item(3, "Charlie")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void itemCarriesIdAndDisplayText() {
        Item item = new Item(42, "Acme");

        assertThat(item.id()).isEqualTo(42L);
        assertThat(item.text()).isEqualTo("Acme");
    }
}
