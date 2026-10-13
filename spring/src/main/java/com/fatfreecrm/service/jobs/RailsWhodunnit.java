package com.fatfreecrm.service.jobs;

import com.fatfreecrm.domain.User;
import java.util.Objects;

public sealed interface RailsWhodunnit
    permits RailsWhodunnit.UserValue, RailsWhodunnit.StringValue,
        RailsWhodunnit.IntegerValue, RailsWhodunnit.NilValue {

    static RailsWhodunnit user(User user) {
        return new UserValue(Objects.requireNonNull(user).getId());
    }

    static RailsWhodunnit string(String userId) {
        return new StringValue(userId);
    }

    static RailsWhodunnit integer(long userId) {
        return new IntegerValue(userId);
    }

    static RailsWhodunnit nil() {
        return NilValue.INSTANCE;
    }

    record UserValue(Long userId) implements RailsWhodunnit {
        public UserValue {
            Objects.requireNonNull(userId);
        }
    }

    record StringValue(String userId) implements RailsWhodunnit {
        public StringValue {
            Objects.requireNonNull(userId);
        }
    }

    record IntegerValue(long userId) implements RailsWhodunnit { }

    enum NilValue implements RailsWhodunnit {
        INSTANCE
    }
}
