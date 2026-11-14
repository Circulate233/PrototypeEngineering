package com.circulation.prototype_eng.api.research.station;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.storage.ImmutableResourceAmounts;
import com.circulation.prototype_eng.testsupport.TestResources;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static com.circulation.prototype_eng.api.research.station.StationFixtures.TYPE;

class ResearchStationsTest {
    @Test
    void totalsIncludeAllLoadedStationsAndAreCappedByRemainingDemand() {
        try (var fixture = new StationFixtures(1, 2)) {
            var first = fixture.station(2, 0);
            var second = fixture.station(2, -1);
            var key = new TestResources.Key(TYPE, "shared");
            Objects.requireNonNull(first.warehouse().input()).insert(key, 2, Actionable.MODULATE);
            Objects.requireNonNull(second.warehouse().input()).insert(key, 5, Actionable.MODULATE);
            fixture.refresh();
            assertEquals(7, fixture.selector.availableMaterials("team", 2, ImmutableResourceAmounts.single(key, 10)).getLong(key));
            assertEquals(3, fixture.selector.availableMaterials("team", 2, ImmutableResourceAmounts.single(key, 3)).getLong(key));
            var capture = Objects.requireNonNull(fixture.selector.capture("team", 2));
            assertEquals(1, capture.rates().size());
            assertEquals(7, capture.availableMaterials(ImmutableResourceAmounts.single(key, 10)).getLong(key));
        }
    }
}
