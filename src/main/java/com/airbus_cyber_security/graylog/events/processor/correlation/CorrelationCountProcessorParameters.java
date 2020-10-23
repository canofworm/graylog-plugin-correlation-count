/*
 * graylog-plugin-correlation-count Source Code
 * Copyright (C) 2018-2020 - Airbus CyberSecurity (SAS) - All rights reserved
 *
 * This file is part of the graylog-plugin-correlation-count GPL Source Code.
 *
 * graylog-plugin-correlation-count Source Code is free software:
 * you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * This code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this code.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.airbus_cyber_security.graylog.events.processor.correlation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.auto.value.AutoValue;
import org.graylog.events.processor.EventProcessorParametersWithTimerange;
import org.graylog2.plugin.indexer.searches.timeranges.AbsoluteRange;
import org.graylog2.plugin.indexer.searches.timeranges.InvalidRangeParametersException;
import org.graylog2.plugin.indexer.searches.timeranges.RelativeRange;
import org.joda.time.DateTime;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@AutoValue
@JsonTypeName(CorrelationCountProcessorConfig.TYPE_NAME)
@JsonDeserialize(builder = CorrelationCountProcessorParameters.Builder.class)
public abstract class CorrelationCountProcessorParameters implements EventProcessorParametersWithTimerange {
    @Override
    public EventProcessorParametersWithTimerange withTimerange(DateTime from, DateTime to) {
        requireNonNull(from, "from cannot be null");
        requireNonNull(to, "to cannot be null");
        checkArgument(to.isAfter(from), "to must be after from");

        return toBuilder().timerange(AbsoluteRange.create(from, to)).build();
    }

    public abstract Builder toBuilder();

    public static Builder builder() {
        return Builder.create();
    }

    @AutoValue.Builder
    public static abstract class Builder implements EventProcessorParametersWithTimerange.Builder<Builder> {
        @JsonCreator
        public static Builder create() {
            final RelativeRange timerange;
            try {
                timerange = RelativeRange.create(3600);
            } catch (InvalidRangeParametersException e) {
                // This should not happen!
                throw new RuntimeException(e);
            }

            return new AutoValue_CorrelationCountProcessorParameters.Builder()
                    .type(CorrelationCountProcessorConfig.TYPE_NAME)
                    .timerange(timerange);
        }

        public abstract CorrelationCountProcessorParameters build();
    }
}
