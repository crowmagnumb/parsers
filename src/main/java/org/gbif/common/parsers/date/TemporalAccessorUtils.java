package org.gbif.common.parsers.date;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQueries;
import java.util.Date;
import javax.annotation.Nullable;



/**
 * Utility methods to work with {@link TemporalAccessor}
 *
 */
public class TemporalAccessorUtils {

  public static ZoneId UTC_ZONE_ID = ZoneOffset.UTC;

  /**
   * Transform a {@link TemporalAccessor} to a {@link java.util.Date}.
   * If the provided {@link TemporalAccessor} contains offset(timezone) information it will be used.
   * See {@link #toDate(TemporalAccessor, boolean)} for more details.
   *
   * @param temporalAccessor
   * @return  the Date object or null if a Date object can not be created
   */
  public static Date toDate(TemporalAccessor temporalAccessor) {
    return toDate(temporalAccessor, false);
  }

  /**
   * Transform a {@link TemporalAccessor} to a {@link java.util.Date}.
   *
   * For {@link YearMonth}, the {@link java.util.Date} will represent the first day of the month.
   * For {@link Year}, the {@link java.util.Date} will represent the first day of January.
   *
   * Remember that a {@link Date} object will always display the date in the current timezone.
   *
   * @param temporalAccessor
   * @param ignoreOffset in case offset information is available in the provided {@link TemporalAccessor}, should it
   *                     be used ?
   * @return the Date object or null if a Date object can not be created
   */
  public static Date toDate(TemporalAccessor temporalAccessor, boolean ignoreOffset) {
    if(temporalAccessor == null){
      return null;
    }

    if(!ignoreOffset && temporalAccessor.isSupported(ChronoField.OFFSET_SECONDS)){
      return Date.from(temporalAccessor.query(ZonedDateTime::from).toInstant());
    }

    if(temporalAccessor.isSupported(ChronoField.SECOND_OF_DAY)){
      return Date.from(temporalAccessor.query(LocalDateTime::from).atZone(UTC_ZONE_ID).toInstant());
    }

    // this may return null in case of partial dates
    LocalDate localDate = temporalAccessor.query(TemporalQueries.localDate());

    // try YearMonth
    if(localDate == null && temporalAccessor.isSupported(ChronoField.MONTH_OF_YEAR)) {
      YearMonth yearMonth = YearMonth.from(temporalAccessor);
      localDate = yearMonth.atDay(1);
    }

    // try Year
    if(localDate == null && temporalAccessor.isSupported(ChronoField.YEAR)) {
      Year year = Year.from(temporalAccessor);
      localDate = year.atDay(1);
    }

    if (localDate != null) {
      return  Date.from(localDate.atStartOfDay(UTC_ZONE_ID).toInstant());
    }

    return null;
  }

  /**
   * Only enable this function when we move to Java 8, do NOT use Guava Optional<>, it introduced some
   * incompatibility in shaded jars (e.g. occurrence)
   *
   * @param ta1
   * @param ta2
   * @return never null
   */
//  public static Optional<? extends TemporalAccessor> getBestResolutionTemporalAccessor(@Nullable TemporalAccessor ta1,
//                                                                                       @Nullable TemporalAccessor ta2){
//    //handle nulls combinations
//    if(ta1 == null && ta2 == null){
//      return Optional.absent();
//    }
//    if(ta1 == null){
//      return Optional.of(ta2);
//    }
//    if(ta2 == null){
//      return Optional.of(ta1);
//    }
//
//    AtomizedLocalDate ymd1 = AtomizedLocalDate.fromTemporalAccessor(ta1);
//    AtomizedLocalDate ymd2 = AtomizedLocalDate.fromTemporalAccessor(ta2);
//
//    // If they both provide the year, it must match
//    if(ymd1.getYear() != null && ymd2.getYear() != null && !ymd1.getYear().equals(ymd2.getYear())){
//      return Optional.absent();
//    }
//    // If they both provide the month, it must match
//    if(ymd1.getMonth() != null && ymd2.getMonth() != null && !ymd1.getMonth().equals(ymd2.getMonth())){
//      return Optional.absent();
//    }
//    // If they both provide the day, it must match
//    if(ymd1.getDay() != null && ymd2.getDay() != null && !ymd1.getDay().equals(ymd2.getDay())){
//      return Optional.absent();
//    }
//
//    if(ymd1.getResolution() > ymd2.getResolution()){
//      return Optional.of(ta1);
//    }
//
//    return Optional.of(ta2);
//  }

  /**
   * The idea of "best resolution" TemporalAccessor is to get the TemporalAccessor that offers more resolution than
   * the other but they must NOT contradict.
   * e.g. 2005-01 and 2005-01-01 will return 2005-01-01.
   *
   * Note that if one of the 2 parameters is null the other one will be considered having the best resolution
   *
   * @param ta1
   * @param ta2
   * @return TemporalAccessor representing the best resolution or null
   */
  public static TemporalAccessor getBestResolutionTemporalAccessor(@Nullable TemporalAccessor ta1,
                                                                    @Nullable TemporalAccessor ta2){
    //handle nulls combinations
    if(ta1 == null && ta2 == null){
      return null;
    }
    if(ta1 == null){
      return ta2;
    }
    if(ta2 == null){
      return ta1;
    }

    AtomizedLocalDate ymd1 = AtomizedLocalDate.fromTemporalAccessor(ta1);
    AtomizedLocalDate ymd2 = AtomizedLocalDate.fromTemporalAccessor(ta2);

    // If they both provide the year, it must match
    if(ymd1.getYear() != null && ymd2.getYear() != null && !ymd1.getYear().equals(ymd2.getYear())){
      return null;
    }
    // If they both provide the month, it must match
    if(ymd1.getMonth() != null && ymd2.getMonth() != null && !ymd1.getMonth().equals(ymd2.getMonth())){
      return null;
    }
    // If they both provide the day, it must match
    if(ymd1.getDay() != null && ymd2.getDay() != null && !ymd1.getDay().equals(ymd2.getDay())){
      return null;
    }

    if(ymd1.getResolution() > ymd2.getResolution()){
      return ta1;
    }
    return ta2;
  }

  /**
   * Given two TemporalAccessor with possibly different resolutions, this method checks if they represent the
   * same YMD.
   * If a null is provided, false will be returned. If one of the 2 TemporalAccessor provides resolution
   * less than YMD, false will be returned.
   *
   * @param ta1
   * @param ta2
   *
   * @return
   */
  public static boolean representsSameYMD(@Nullable TemporalAccessor ta1, @Nullable TemporalAccessor ta2) {

    //handle nulls combinations
    if (ta1 == null || ta2 == null) {
      return false;
    }

    AtomizedLocalDate ymd1 = AtomizedLocalDate.fromTemporalAccessor(ta1);
    AtomizedLocalDate ymd2 = AtomizedLocalDate.fromTemporalAccessor(ta2);

    // we only deal with complete Local Date
    if (!ymd1.isComplete() || !ymd2.isComplete()) {
      return false;
    }
    return ymd1.equals(ymd2);
  }

}
