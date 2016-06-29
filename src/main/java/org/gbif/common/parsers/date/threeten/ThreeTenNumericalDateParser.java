package org.gbif.common.parsers.date.threeten;

import org.gbif.common.parsers.core.Parsable;
import org.gbif.common.parsers.core.ParseResult;
import org.gbif.common.parsers.date.DateFormatHint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.threeten.bp.LocalDate;
import org.threeten.bp.LocalDateTime;
import org.threeten.bp.Year;
import org.threeten.bp.YearMonth;
import org.threeten.bp.ZonedDateTime;
import org.threeten.bp.format.DateTimeFormatter;
import org.threeten.bp.format.DateTimeFormatterBuilder;
import org.threeten.bp.format.DateTimeParseException;
import org.threeten.bp.format.ResolverStyle;
import org.threeten.bp.format.SignStyle;
import org.threeten.bp.temporal.ChronoField;
import org.threeten.bp.temporal.TemporalAccessor;

/**
 * Numerical DateParser based on threetenbp (JSR310 backport) library which also means it is almost ready for Java 8.
 * This is a numerical Date Parser which means it is not responsible to parse dates that contains text for representing
 * a part of the dates (e.g. January 1 1980)
 *
 * Months are in numerical value starting at 1 for January.
 *
 * Note that LocalDateTime and LocalDate are TimeZone agnostic.
 *
 * Be aware that LocalDate and LocalDateTime doesn't map correctly to Date object for all dates before the
 * Gregorian cut-off date (1582-10-15). To transform a such date use GregorianCalendar by setting the date according
 * to the TemporalAccessor you got back from that class.
 *
 * Thread-Safe after creation.
 *
 */
public class ThreeTenNumericalDateParser implements Parsable<TemporalAccessor> {

  private static final Logger LOGGER = LoggerFactory.getLogger(ThreeTenNumericalDateParser.class);

  public static final Pattern OPTIONAL_PATTERN_PART = Pattern.compile("\\[.*\\]");

  // ISO 8601 specifies a Unicode minus (CHAR_MINUS), with a hyphen (CHAR_HYPHEN) as an alternative.
  public static final char CHAR_HYPHEN = '\u002d'; // Unicode hyphen, U+002d, char '-'
  public static final char CHAR_MINUS = '\u2212'; // Unicode minus, U+2212, char '−'

  private static final Map<DateFormatHint, List<ThreeTenDateTimeParser>> FORMATTERS_BY_HINT = Maps.newHashMap();

  // DateTimeFormatter includes some ISO parsers but just to make it explicit we define our own
  private static final DateTimeFormatter ISO_PARSER = (new DateTimeFormatterBuilder()
          .appendValue(ChronoField.YEAR, 2, 4, SignStyle.NEVER)
          .optionalStart().appendLiteral('-')
          .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, SignStyle.NEVER)
          .optionalStart().appendLiteral('-')
          .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NEVER))
          .optionalEnd()
          .optionalEnd()
          .toFormatter().withResolverStyle(ResolverStyle.STRICT);

  //brackets [] represent optional section of the pattern
  //separator is a CHAR_HYPHEN
  private static final List<ThreeTenDateTimeParser> BASE_PARSER_LIST = ImmutableList.copyOf(
          ThreeTenNumericalDateParserBuilder.newParserListBuilder()
                  .appendDateTimeParser("uuuuMMdd", DateFormatHint.YMD)
                  .appendDateTimeParser("uuuu-M-d[ HH:mm:ss]", DateFormatHint.YMDT, String.valueOf(CHAR_HYPHEN),
                          String.valueOf(CHAR_MINUS) + ".")
                  .appendDateTimeParser("uuuu-M-d'T'HH[:mm[:ss]]", DateFormatHint.YMDT)
                  .appendDateTimeParser("uuuu-M-d'T'HHmm[ss]", DateFormatHint.YMDT)
                  .appendDateTimeParser("uuuu-M-d'T'HH:mm:ssZ", DateFormatHint.YMDT)
                  .appendDateTimeParser("uuuu-M-d'T'HH:mm:ssxxx", DateFormatHint.YMDT) //covers 1978-12-21T02:12:43+01:00
                  .appendDateTimeParser("uuuu-M-d'T'HH:mm:ss'Z'", DateFormatHint.YMDT)
                  .appendDateTimeParser("uuuu-M", DateFormatHint.YM)
                  .appendDateTimeParser("uuuu", DateFormatHint.Y)
                  .appendDateTimeParser("uuuu年MM月dd日", DateFormatHint.HAN)
                  .appendDateTimeParser("uuuu年M月d日", DateFormatHint.HAN)
                  .appendDateTimeParser("uu年M月d日", DateFormatHint.HAN)
                  .build()
  );

  // Possibly ambiguous dates will record an error in case more than one pattern can be applied
  private static final List<ThreeTenDateTimeMultiParser> MULTIPARSER_PARSER_LIST = ImmutableList.of(
          ThreeTenNumericalDateParserBuilder.newMultiParserListBuilder()
                  .preferredDateTimeParser("d.M.uuuu", DateFormatHint.DMY) //DE, DK, NO
                  .appendDateTimeParser("M.d.uuuu", DateFormatHint.MDY)
                  .build(),
          // the followings are mostly derived of the difference between FR,GB,ES (DMY) format and US format (MDY)
          ThreeTenNumericalDateParserBuilder.newMultiParserListBuilder()
                  .appendDateTimeParser("d/M/uuuu", DateFormatHint.DMY, "/", String.valueOf(CHAR_HYPHEN) + String.valueOf(CHAR_MINUS))
                  .appendDateTimeParser("M/d/uuuu", DateFormatHint.MDY, "/", String.valueOf(CHAR_HYPHEN) + String.valueOf(CHAR_MINUS))
                  .build(),
          ThreeTenNumericalDateParserBuilder.newMultiParserListBuilder()
                  .appendDateTimeParser("ddMMuuuu", DateFormatHint.DMY)
                  .appendDateTimeParser("MMdduuuu", DateFormatHint.MDY)
                  .build(),
          // the followings are not officially supported by any countries but are sometimes used
          ThreeTenNumericalDateParserBuilder.newMultiParserListBuilder()
                  .appendDateTimeParser("d\\M\\uuuu", DateFormatHint.DMY, "\\", "_")
                  .appendDateTimeParser("M\\d\\uuuu", DateFormatHint.MDY, "\\", "_")
                  .build()
  );

  static{
    for(ThreeTenDateTimeParser parser : BASE_PARSER_LIST){
      //TODO: when updated to Java 8 FORMATTERS_BY_HINT.putIfAbsent(parser.getHint(), new ArrayList<ThreeTenDateTimeParser>());
      if(!FORMATTERS_BY_HINT.containsKey(parser.getHint())){
        FORMATTERS_BY_HINT.put(parser.getHint(), new ArrayList<ThreeTenDateTimeParser>());
      }
      // end TODO
      FORMATTERS_BY_HINT.get(parser.getHint()).add(parser);
    }

    for(ThreeTenDateTimeMultiParser parserAmbiguity : MULTIPARSER_PARSER_LIST){
      for(ThreeTenDateTimeParser parser : parserAmbiguity.getAllParsers()) {
        //TODO: when updated to Java 8 FORMATTERS_BY_HINT.putIfAbsent(parser.getHint(), new ArrayList<ThreeTenDateTimeParser>());
        if(!FORMATTERS_BY_HINT.containsKey(parser.getHint())){
          FORMATTERS_BY_HINT.put(parser.getHint(), new ArrayList<ThreeTenDateTimeParser>());
        }
        // end TODO
        FORMATTERS_BY_HINT.get(parser.getHint()).add(parser);
      }
    }
  }

  // the active list/map are related to a specific instance
  private final Map<DateFormatHint, List<ThreeTenDateTimeParser>> activeFormattersByHint;
  private final List<ThreeTenDateTimeMultiParser> activeMultiParserList;

  /**
   * Get an instance of a default ThreeTenNumericalDateParser.
   *
   * @return
   */
  public static ThreeTenNumericalDateParser newInstance(){
    return new ThreeTenNumericalDateParser();
  }

  /**
   * Get an instance of a ThreeTenNumericalDateParser from a base year.
   * Base year is used to handle year represented by 2 digits.
   * @param baseYear
   * @return
   */
  public static ThreeTenNumericalDateParser newInstance(Year baseYear){
    return new ThreeTenNumericalDateParser(baseYear);
  }

  /**
   * Private constructor, use static methods {@link #newInstance()} and {@link #newInstance(Year)}.
   */
  private ThreeTenNumericalDateParser() {
    this.activeFormattersByHint = ImmutableMap.copyOf(FORMATTERS_BY_HINT);
    this.activeMultiParserList = MULTIPARSER_PARSER_LIST;
  }

  private ThreeTenNumericalDateParser(Year baseYear) {

    Preconditions.checkState(baseYear.getValue() <= LocalDate.now().getYear(), "Base year is less or equals to" +
              " the current year");

    Map<DateFormatHint, List<ThreeTenDateTimeParser>> formattersByHint = Maps.newHashMap(FORMATTERS_BY_HINT);

    List<ThreeTenDateTimeMultiParser> multiParserList = Lists.newArrayList(MULTIPARSER_PARSER_LIST);
    multiParserList.addAll(Lists.newArrayList(
            ThreeTenNumericalDateParserBuilder.newMultiParserListBuilder()
                    .preferredDateTimeParser("d.M.uu", DateFormatHint.DMY, baseYear) //DE, DK, NO
                    .appendDateTimeParser("M.d.uu", DateFormatHint.MDY, baseYear)
                    .build(),
            ThreeTenNumericalDateParserBuilder.newMultiParserListBuilder()
                    .appendDateTimeParser("d/M/uu", DateFormatHint.DMY, "/",
                            String.valueOf(CHAR_HYPHEN) + String.valueOf(CHAR_MINUS), baseYear)
                    .appendDateTimeParser("M/d/uu", DateFormatHint.MDY, "/",
                            String.valueOf(CHAR_HYPHEN) + String.valueOf(CHAR_MINUS), baseYear)
                    .build(),
            ThreeTenNumericalDateParserBuilder.newMultiParserListBuilder()
                    .appendDateTimeParser("ddMMuu", DateFormatHint.DMY, baseYear)
                    .appendDateTimeParser("MMdduu", DateFormatHint.MDY, baseYear)
                    .build(),
            ThreeTenNumericalDateParserBuilder.newMultiParserListBuilder()
                    .appendDateTimeParser("d\\M\\uu", DateFormatHint.DMY, "\\", "_", baseYear)
                    .appendDateTimeParser("M\\d\\uu", DateFormatHint.MDY, "\\", "_", baseYear)
                    .build()
    ));

    for(ThreeTenDateTimeMultiParser multiParser : multiParserList){
      for(ThreeTenDateTimeParser parser : multiParser.getAllParsers()) {
        //TODO: when updated to Java 8 formattersByHint.putIfAbsent(parser.getHint(), new ArrayList<ThreeTenDateTimeParser>());
        if(!formattersByHint.containsKey(parser.getHint())){
          formattersByHint.put(parser.getHint(), new ArrayList<ThreeTenDateTimeParser>());
        }
        // end TODO
        formattersByHint.get(parser.getHint()).add(parser);
      }
    }

    this.activeMultiParserList = ImmutableList.copyOf(multiParserList);
    this.activeFormattersByHint = ImmutableMap.copyOf(formattersByHint);
  }

  @Override
  public ParseResult<TemporalAccessor> parse(String input) {
    return parse(input, DateFormatHint.NONE);
  }

  /**
   * Parse year, month, day strings as a TemporalAccessor.
   *
   * @param year
   * @param month
   * @param day
   * @return
   */
  public static ParseResult<TemporalAccessor> parse(@Nullable String year, @Nullable String month, @Nullable String day) {

    // avoid possible misinterpretation when month is not provided (but day is)
    if(StringUtils.isBlank(month) && StringUtils.isNotBlank(day)){
      return ParseResult.fail();
    }

    String date = Joiner.on(CHAR_HYPHEN).skipNulls().join(Strings.emptyToNull(year), Strings.emptyToNull(month),
            Strings.emptyToNull(day));
    TemporalAccessor tp = tryParse(date, ISO_PARSER, null);

    if(tp != null){
      return ParseResult.success(ParseResult.CONFIDENCE.DEFINITE, tp);
    }
    return ParseResult.fail();
  }

  /**
   * Parse year, month, day integers as a TemporalAccessor.
   *
   * @param year
   * @param month
   * @param day
   * @return
   */
  public static ParseResult<TemporalAccessor> parse(@Nullable Integer year, @Nullable Integer month, @Nullable Integer day) {

    // avoid possible misinterpretation when month is not provided (but day is)
    if(month == null && day != null){
      return ParseResult.fail();
    }

    String date = Joiner.on(CHAR_HYPHEN).skipNulls().join(year, month, day);
    TemporalAccessor tp = tryParse(date, ISO_PARSER, null);

    if(tp != null){
      return ParseResult.success(ParseResult.CONFIDENCE.DEFINITE, tp);
    }
    return ParseResult.fail();
  }

  /**
   * Parse a date represented as a single String into a TemporalAccessor.
   *
   * @param input
   * @param hint help to speed up the parsing and possibly return a better confidence
   * @return
   */
  public ParseResult<TemporalAccessor> parse(String input, DateFormatHint hint) {

    if(StringUtils.isBlank(input)){
      return ParseResult.fail();
    }
    // make sure hint is never null
    if(hint == null){
      hint = DateFormatHint.NONE;
    }

    List<ThreeTenDateTimeParser> parserList = activeFormattersByHint.containsKey(hint) ? activeFormattersByHint.get(hint) : BASE_PARSER_LIST;

    // First attempt: find a match with definite confidence
    TemporalAccessor parsedTemporalAccessor;
    for(ThreeTenDateTimeParser parser : parserList){
      parsedTemporalAccessor = parser.parse(input);
      if(parsedTemporalAccessor != null){
        return ParseResult.success(ParseResult.CONFIDENCE.DEFINITE, parsedTemporalAccessor);
      }
    }

    // if a format hint was provided we already tried all possible format
    if( hint != DateFormatHint.NONE){
      return ParseResult.fail();
    }

    // Second attempt: find one or multiple match(es) in the list of ThreeTenDateTimeMultiParser
    int numberOfPossiblyAmbiguousMatch = 0;
    TemporalAccessor lastParsedSuccess = null;
    TemporalAccessor lastParsedPreferred = null;
    // Is that all results are equal (the represent the same TemporalAccessor) in case there is no preferred result defined
    boolean lastParsedSuccessOtherResultsEqual = false;
    ThreeTenDateTimeMultiParser.MultipleParseResult result;

    // here we do not stop when we find a match, we try them all to check for a possible ambiguity
    for(ThreeTenDateTimeMultiParser parserAmbiguity : activeMultiParserList){
      result = parserAmbiguity.parse(input);
      numberOfPossiblyAmbiguousMatch += result.getNumberParsed();

      if(result.getNumberParsed() > 0){
        lastParsedSuccess = result.getResult();

        // make sure to log in case lastParsedSuccessEqual already equals true
        if (lastParsedSuccessOtherResultsEqual) {
          LOGGER.warn("Issue with ThreeTenDateTimeMultiParser configuration: Input {} produces more results even " +
                  "if lastParsedSuccessEqual is set to true.", input);
        }
        lastParsedSuccessOtherResultsEqual = false;

        if(result.getPreferredResult() != null){
          if(lastParsedPreferred != null){
            LOGGER.warn("Issue with ThreeTenDateTimeMultiParser configuration: Input {} produces 2 preferred results", input);
          }
          lastParsedPreferred = result.getPreferredResult();
        }
        else{
          //if we have no PreferredResult but all results represent the same TemporalAccessor
          if(result.getOtherResults() != null && result.getOtherResults().size() > 1) {
            lastParsedSuccessOtherResultsEqual = allEquals(result.getOtherResults());
          }
        }
      }
    }

    //if there is only one pattern that can be applied it is not ambiguous
    if(numberOfPossiblyAmbiguousMatch == 1){
      return ParseResult.success(ParseResult.CONFIDENCE.DEFINITE, lastParsedSuccess);
    }
    else if ( numberOfPossiblyAmbiguousMatch > 1 ){

      //if all the possible ambiguities are equal, there is no ambiguity
      if(lastParsedSuccessOtherResultsEqual){
        return ParseResult.success(ParseResult.CONFIDENCE.DEFINITE, lastParsedSuccess);
      }

      //check if we have result from the preferred parser
      if(lastParsedPreferred != null){
        return ParseResult.success(ParseResult.CONFIDENCE.PROBABLE, lastParsedPreferred);
      }
    }

    LOGGER.debug("Number of matches for {} : {}", input, numberOfPossiblyAmbiguousMatch);
    return ParseResult.fail();
  }

  /**
   * Utility private method to avoid throwing a runtime exception when the formatter can not parse the String.
   * TODO normalizer is call too often maybe this class should not take it and only try to parse
   * @param input
   * @param formatter
   * @param normalizer
   * @return
   */
  private static TemporalAccessor tryParse(String input, DateTimeFormatter formatter, DateTimeSeparatorNormalizer normalizer){

    if(normalizer != null){
      input = normalizer.normalize(input);
    }
    try {
      return formatter.parseBest(input, ZonedDateTime.FROM, LocalDateTime.FROM, LocalDate.FROM, YearMonth.FROM, Year.FROM);
    }
    catch (DateTimeParseException dpe){}
    return null;
  }

  /**
   * Check if all the TemporalAccessor of the list are equal.
   * If the list contains 0 element is will return false, if the list contains 1 element it will return true.
   * @param taList
   * @return
   */
  private static boolean allEquals(List<TemporalAccessor> taList){

    if(taList == null || taList.isEmpty()){
      return false;
    }

    TemporalAccessor reference = null;
    boolean allEqual = false;
    for(TemporalAccessor ta: taList){
      if(reference == null){
        reference = ta;
      }
      allEqual = reference.equals(ta);
    }
    return allEqual;
  }

}
