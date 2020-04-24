package com.airbus_cyber_security.graylog;

import com.airbus_cyber_security.graylog.config.CorrelationCountProcessorConfig;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import org.apache.logging.log4j.core.config.Order;
import org.graylog2.indexer.results.CountResult;
import org.graylog2.indexer.results.ResultMessage;
import org.graylog2.indexer.results.SearchResult;
import org.graylog2.indexer.results.TermsResult;
import org.graylog2.indexer.searches.Searches;
import org.graylog2.indexer.searches.Sorting;
import org.graylog2.plugin.Message;
import org.graylog2.plugin.MessageSummary;
import org.graylog2.plugin.indexer.searches.timeranges.AbsoluteRange;
import org.graylog2.plugin.indexer.searches.timeranges.InvalidRangeParametersException;
import org.graylog2.plugin.indexer.searches.timeranges.RelativeRange;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class CorrelationCountUtils {
    private static final Logger LOG = LoggerFactory.getLogger(CorrelationCountUtils.class.getSimpleName());

    private static final String HEADER_STREAM = "streams:";

    enum ThresholdType {

        MORE("more than"),
        LESS("less than");

        private final String description;

        ThresholdType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }

        public static ThresholdType fromString(String text) {
            for (ThresholdType type : ThresholdType.values()) {
                if (type.description.equalsIgnoreCase(text)) {
                    return type;
                }
            }
            return null;
        }
    }

    enum OrderType {

        ANY("any order"),
        BEFORE("additional messages before main messages"),
        AFTER("additional messages after main messages");

        private final String description;

        OrderType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }

        public static OrderType fromString(String type) {
            for (OrderType orderType : OrderType.values()) {
                if (orderType.description.equals(type)) {
                    return orderType;
                }
            }
            return null;
        }
    }

    /*public interface Factory extends AlertCondition.Factory {
        @Override
        CorrelationCountUtils create(Stream stream,
                                @Assisted("id") String id,
                                DateTime createdAt,
                                @Assisted("userid") String creatorUserId,
                                Map<String, Object> parameters,
                                @Assisted("title") @Nullable String title);

        @Override
        CorrelationCountConfig config();

        @Override
        CorrelationCount.Descriptor descriptor();
    }*/



/*
    @AssistedInject
    public CorrelationCountUtils(Searches searches,
                            @Assisted Stream stream,
                            @Nullable @Assisted("id") String id,
                            @Assisted DateTime createdAt,
                            @Assisted("userid") String creatorUserId,
                            @Assisted Map<String, Object> parameters,
                            @Assisted("title") @Nullable String title) {
        super(stream, id, CorrelationCountUtils.class.getCanonicalName(), createdAt, creatorUserId, parameters, title);
    }*/

    /*@Override
    public String getDescription() {
        return "time: " + time
                + ", main_threshold_type: " + thresholdType.toString().toLowerCase(Locale.ENGLISH)
                + ", main_threshold: " + threshold
                + ", grace: " + grace
                + ", repeat notifications: " + repeatNotifications;
    }*/

    private static boolean isTriggered(CorrelationCountUtils.ThresholdType thresholdType, int threshold, long count) {
        return (((thresholdType == CorrelationCountUtils.ThresholdType.MORE) && (count > threshold)) ||
                ((thresholdType == CorrelationCountUtils.ThresholdType.LESS) && (count < threshold)));
    }

    private static void addSearchMessages(Searches searches, List<MessageSummary> summaries, String searchQuery, String filter, AbsoluteRange range, int messageBacklog) {
        final SearchResult backlogResult = searches.search(searchQuery, filter,
                range, messageBacklog, 0, new Sorting(Message.FIELD_TIMESTAMP, Sorting.Direction.DESC));
        for (ResultMessage resultMessage : backlogResult.getResults()) {
            summaries.add(new MessageSummary(resultMessage.getIndex(), resultMessage.getMessage()));
        }
    }

    private static String buildSearchQuery(String firstField, List<String> nextFields, String matchedFieldValue, String searchQuery) {
        for (String field : nextFields) {
            matchedFieldValue = matchedFieldValue.replaceFirst(" - ", " AND " + field + ": ");
        }
        return (searchQuery + " AND " + firstField + ": " + matchedFieldValue);
    }

    private static List<DateTime> getListOrderTimestamp(List<MessageSummary> summaries, CorrelationCountUtils.OrderType messagesOrderType){
        List<DateTime> listDate = new ArrayList<>();
        for (MessageSummary messageSummary : summaries) {
            listDate.add(messageSummary.getTimestamp());
        }
        Collections.sort(listDate);
        if(messagesOrderType.equals(CorrelationCountUtils.OrderType.AFTER)) {
            Collections.reverse(listDate);
        }
        return listDate;
    }

    /*
     * Check that the Second Stream is before or after the first stream
     */
    @VisibleForTesting
    protected static boolean checkOrderSecondStream(List<MessageSummary> summariesFirstStream, List<MessageSummary> summariesSecondStream, CorrelationCountProcessorConfig config) {
        int countFirstStream = summariesFirstStream.size();
        CorrelationCountUtils.OrderType messagesOrder = CorrelationCountUtils.OrderType.fromString(config.messagesOrder());
        List<DateTime> listDateFirstStream = getListOrderTimestamp(summariesFirstStream, messagesOrder);
        List<DateTime> listDateSecondStream = getListOrderTimestamp(summariesSecondStream, messagesOrder);

        for (DateTime dateFirstStream : listDateFirstStream) {
            int countSecondStream = 0;
            for (DateTime dateSecondStream : listDateSecondStream) {
                if(	(messagesOrder.equals(CorrelationCountUtils.OrderType.BEFORE) && dateSecondStream.isBefore(dateFirstStream)) ||
                        (messagesOrder.equals(CorrelationCountUtils.OrderType.AFTER) && dateSecondStream.isAfter(dateFirstStream))){
                    countSecondStream++;
                }else {
                    break;
                }
            }
            if(isTriggered(CorrelationCountUtils.ThresholdType.fromString(config.mainThresholdType()),config.mainThreshold(),countFirstStream)
                    && isTriggered(CorrelationCountUtils.ThresholdType.fromString(config.additionalThresholdType()),config.additionalThreshold(),countSecondStream)) {
                return true;
            }
            countFirstStream--;
        }
        return false;
    }

    private static String getResultDescription(long countMainStream, long countAdditionalStream, CorrelationCountProcessorConfig config) {

        String msgCondition;
        if(CorrelationCountUtils.OrderType.fromString(config.messagesOrder()).equals(CorrelationCountUtils.OrderType.ANY)) {
            msgCondition = "and";
        } else {
            msgCondition = config.messagesOrder();//messagesOrder.getDescription();
        }

        String resultDescription = "The additional stream had " + countAdditionalStream + " messages with trigger condition "
                + config.additionalThresholdType().toLowerCase(Locale.ENGLISH) + " than " + config.additionalThreshold()
                + " messages " + msgCondition + " the main stream had " + countMainStream + " messages with trigger condition "
                + config.mainThresholdType().toLowerCase(Locale.ENGLISH) + " than " + config.mainThreshold() + " messages in the last " + config.timeRange() + " minutes";

        if(!config.groupingFields().isEmpty()) {
            resultDescription = resultDescription+" with the same value of the fields " + String.join(", ",config.groupingFields());
        }

        return resultDescription+". (Current grace time: " + config.gracePeriod() + " minutes)";
    }

    private static boolean isRuleTriggered(List<MessageSummary> summariesMainStream, List<MessageSummary> summariesAdditionalStream, CorrelationCountProcessorConfig config) {
        boolean ruleTriggered = true;
        if(CorrelationCountUtils.OrderType.fromString(config.messagesOrder()).equals(CorrelationCountUtils.OrderType.BEFORE)
                || CorrelationCountUtils.OrderType.fromString(config.messagesOrder()).equals(CorrelationCountUtils.OrderType.AFTER)) {
            ruleTriggered = checkOrderSecondStream(summariesMainStream, summariesAdditionalStream, config);
        }
        return ruleTriggered;
    }

    public static CorrelationCountCheckResult runCheckCorrelationCount(Searches searches, CorrelationCountProcessorConfig config) {
        try {
            final RelativeRange relativeRange = RelativeRange.create(config.timeRange() * 60);
            final AbsoluteRange range = AbsoluteRange.create(relativeRange.getFrom(), relativeRange.getTo());
            final String filterMainStream = HEADER_STREAM + "stream_test"; //HEADER_STREAM + stream.getId();//TODO
            final CountResult resultMainStream = searches.count(config.searchQuery(), range, filterMainStream);
            final String filterAdditionalStream = HEADER_STREAM + config.additionalStream();
            final CountResult resultAdditionalStream = searches.count(config.searchQuery(), range, filterAdditionalStream);

            //LOG.debug("Alert check <{}> result: [{}]", id, resultAdditionalStream.count());
            LOG.debug("Alert check <{}> result: [{}]", "id_test", resultAdditionalStream.count());

            if(isTriggered(CorrelationCountUtils.ThresholdType.fromString(config.mainThresholdType()), config.mainThreshold(), resultMainStream.count()) &&
                    isTriggered(CorrelationCountUtils.ThresholdType.fromString(config.additionalThresholdType()), config.additionalThreshold(), resultAdditionalStream.count())) {
                final List<MessageSummary> summaries = Lists.newArrayList();
                final List<MessageSummary> summariesMainStream = Lists.newArrayList();
                final List<MessageSummary> summariesAdditionalStream = Lists.newArrayList();

                if (config.messageBacklog() > 0 || !CorrelationCountUtils.OrderType.valueOf(config.messagesOrder()).equals(CorrelationCountUtils.OrderType.ANY)) {
                    addSearchMessages(searches, summariesMainStream, config.searchQuery(), filterMainStream, range, config.messageBacklog());
                    addSearchMessages(searches, summariesAdditionalStream, config.searchQuery(), filterAdditionalStream, range, config.messageBacklog());
                }

                if(isRuleTriggered(summariesMainStream, summariesAdditionalStream, config)) {
                    if(config.messageBacklog() > 0) {
                        summaries.addAll(summariesMainStream);
                        summaries.addAll(summariesAdditionalStream);
                    }
                    String resultDescription = getResultDescription(resultMainStream.count(), resultAdditionalStream.count(), config);
                    return new CorrelationCountCheckResult(resultDescription, summaries);
                    //return new AbstractAlertCondition.CheckResult(true, this, resultDescription, Tools.nowUTC(), summaries);
                }
            }
            return new CorrelationCountCheckResult("", new ArrayList<>());
            //return new AbstractAlertCondition.NegativeCheckResult();
        } catch (InvalidRangeParametersException e) {
            LOG.error("Invalid timerange.", e);
            return null;
        }
    }

    private static Map<String, Long[]> getMatchedTerms(TermsResult termResult, TermsResult termResultAdditionalStrem){

        Map<String, Long[]> matchedTerms = new HashMap<>();
        for (Map.Entry<String, Long> term : termResult.getTerms().entrySet()) {
            Long termAdditionalStreamValue = termResultAdditionalStrem.getTerms().getOrDefault(term.getKey(), 0L);
            matchedTerms.put(term.getKey(), new Long[] {term.getValue(), termAdditionalStreamValue});
        }
        for (Map.Entry<String, Long> termAdditionalStream : termResultAdditionalStrem.getTerms().entrySet()) {
            if(!matchedTerms.containsKey(termAdditionalStream.getKey())){
                matchedTerms.put(termAdditionalStream.getKey(), new Long[] {0L, termAdditionalStream.getValue()});
            }
        }

        return matchedTerms;
    }

    public static CorrelationCountCheckResult runCheckCorrelationWithFields(Searches searches, CorrelationCountProcessorConfig config) {
        try {
            final RelativeRange relativeRange = RelativeRange.create(config.timeRange() * 60);
            final AbsoluteRange range = AbsoluteRange.create(relativeRange.getFrom(), relativeRange.getTo());
            final String filterMainStream = HEADER_STREAM + "stream_test"; //HEADER_STREAM + stream.getId();//TODO
            final String filterAdditionalStream = HEADER_STREAM + config.additionalStream();
            boolean ruleTriggered=false;
            Integer backlogSize = config.messageBacklog();
            boolean backlogEnabled = false;
            int searchLimit = 100;
            if(backlogSize != null && backlogSize > 0) {
                backlogEnabled = true;
                searchLimit = backlogSize;
            }

            List<String> nextFields = new ArrayList<>(config.groupingFields());
            String firstField = config.groupingFields().iterator().next();
            nextFields.remove(0);

            TermsResult termResult = searches.terms(firstField, nextFields, searchLimit, config.searchQuery(), filterMainStream, range, Sorting.Direction.DESC);
            TermsResult termResultAdditionalStrem = searches.terms(firstField, nextFields, searchLimit, config.searchQuery(), filterAdditionalStream, range, Sorting.Direction.DESC);
            Map<String, Long[]> matchedTerms = getMatchedTerms(termResult, termResultAdditionalStrem);

            long countFirstMainStream = 0;
            long countFirstAdditionalStream = 0;
            boolean isFirstTriggered = true;
            final List<MessageSummary> summaries = Lists.newArrayList();
            for (Map.Entry<String, Long[]> matchedTerm : matchedTerms.entrySet()) {
                String matchedFieldValue = matchedTerm.getKey();
                Long[] counts = matchedTerm.getValue();

                if(isTriggered(CorrelationCountUtils.ThresholdType.valueOf(config.mainThresholdType()),config.mainThreshold(),counts[0])
                        && isTriggered(CorrelationCountUtils.ThresholdType.valueOf(config.additionalThresholdType()),config.additionalThreshold(),counts[1])) {
                    final List<MessageSummary> summariesMainStream = Lists.newArrayList();
                    final List<MessageSummary> summariesAdditionalStream = Lists.newArrayList();

                    if (backlogEnabled ||  !CorrelationCountUtils.OrderType.valueOf(config.messagesOrder()).equals(CorrelationCountUtils.OrderType.ANY)) {
                        String searchQuery = buildSearchQuery(firstField, nextFields, matchedFieldValue, config.searchQuery());

                        addSearchMessages(searches, summariesMainStream, searchQuery, filterMainStream, range, config.messageBacklog());
                        addSearchMessages(searches, summariesAdditionalStream, searchQuery, filterAdditionalStream, range, config.messageBacklog());
                    }

                    if(isRuleTriggered(summariesMainStream, summariesAdditionalStream, config)) {
                        ruleTriggered = true;
                        if(isFirstTriggered) {
                            countFirstMainStream = counts[0];
                            countFirstAdditionalStream = counts[1];
                            isFirstTriggered = false;
                        }
                        if(backlogSize > 0) {
                            summaries.addAll(summariesMainStream);
                            summaries.addAll(summariesAdditionalStream);
                        }
                    }
                }
            }

            if(ruleTriggered) {
                String resultDescription = getResultDescription(countFirstMainStream, countFirstAdditionalStream, config);
                return new CorrelationCountCheckResult(resultDescription, summaries);
                //return new AbstractAlertCondition.CheckResult(true, this, resultDescription, Tools.nowUTC(), summaries);
            }
            return new CorrelationCountCheckResult("", new ArrayList<>());
            //return new AbstractAlertCondition.NegativeCheckResult();
        } catch (InvalidRangeParametersException e) {
            LOG.error("Invalid timerange.", e);
            return null;
        }
    }
}