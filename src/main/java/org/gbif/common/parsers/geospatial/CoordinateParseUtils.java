package org.gbif.common.parsers.geospatial;

import org.gbif.api.vocabulary.OccurrenceIssue;
import org.gbif.common.parsers.core.Parsable;
import org.gbif.common.parsers.core.ParseResult;
import org.gbif.common.parsers.utils.NumberParser;

import java.util.EnumSet;
import java.util.Set;

import com.google.common.base.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utilities for assisting in the parsing of latitude and longitude strings into Decimals.
 */
public class CoordinateParseUtils {

  private CoordinateParseUtils() {
    throw new UnsupportedOperationException("Can't initialize class");
  }

  private static final Logger LOG = LoggerFactory.getLogger(CoordinateParseUtils.class);

  /**
   * This parses string representations of latitude and longitude values. It tries its best to interpret the values and
   * indicates any problems in its result as {@link org.gbif.api.vocabulary.OccurrenceIssue}.
   * When the {@link ParseResult.STATUS} is FAIL the payload will be null and one or more issues should be set
   * in {@link ParseResult#getIssues()}.
   *
   * Coordinate precision will be 5 decimals at most, any more precise values will be rounded.
   *
   * @param latitude  The decimal latitude
   * @param longitude The decimal longitude
   *
   * @return The parse result
   */
  public static ParseResult<LatLng> parseLatLng(final String latitude, final String longitude) {
    return new Parsable<LatLng>() {
      @Override
      public ParseResult<LatLng> parse(String v) {
        if (Strings.isNullOrEmpty(latitude) && Strings.isNullOrEmpty(longitude)) {
          return ParseResult.fail();
        }
        Double lat = NumberParser.parseDouble(latitude);
        Double lng = NumberParser.parseDouble(longitude);

        if (lat == null || lng == null) {
          return ParseResult.fail(OccurrenceIssue.COORDINATE_INVALID);
        }

        // collecting issues for result
        Set<OccurrenceIssue> issues = EnumSet.noneOf(OccurrenceIssue.class);

        // round to 5 decimals
        final double latOrig = lat;
        final double lngOrig = lng;
        lat = roundTo5decimals(lat);
        lng = roundTo5decimals(lng);
        if (Double.compare(lat, latOrig) != 0 || Double.compare(lng, lngOrig) != 0) {
          issues.add(OccurrenceIssue.COORDINATE_ROUNDED);
        }

        // 0,0 is too suspicious
        if (Double.compare(lat, 0) == 0 && Double.compare(lng, 0) == 0) {
          issues.add(OccurrenceIssue.ZERO_COORDINATE);
          return ParseResult
            .success(ParseResult.CONFIDENCE.POSSIBLE, new LatLng(0, 0), issues);
        }

        // if everything falls in range
        if (Double.compare(lat, 90) <= 0 && Double.compare(lat, -90) >= 0 && Double.compare(lng, 180) <= 0
            && Double.compare(lng, -180) >= 0) {
          return ParseResult.success(ParseResult.CONFIDENCE.DEFINITE, new LatLng(lat, lng), issues);
        }

        // if lat is out of range, but in range of the lng,
        // assume swapped coordinates.
        // note that should we desire to trust the following records, we would need to clear the flag for the records to
        // appear in
        // search results and maps etc. however, this is logic decision, that goes above the capabilities of this method
        if (Double.compare(lat, 90) > 0 || Double.compare(lat, -90) < 0) {

          // try and swap
          if (Double.compare(lng, 90) <= 0 && Double.compare(lng, -90) >= 0 && Double.compare(lat, 180) <= 0
              && Double.compare(lat, -180) >= 0) {
            issues.add(OccurrenceIssue.PRESUMED_SWAPPED_COORDINATE);
            return ParseResult.fail(new LatLng(lat, lng), issues);
          }
        }

        // then something is out of range
        issues.add(OccurrenceIssue.COORDINATE_OUT_OF_RANGE);
        return ParseResult.fail(issues);
      }
    }.parse(null);
  }

  // round to 5 decimals (~1m precision) since no way we're getting anything legitimately more precise
  private static Double roundTo5decimals(Double x) {
    return x == null ? null : Math.round(x * Math.pow(10, 5)) / Math.pow(10, 5);
  }
}
