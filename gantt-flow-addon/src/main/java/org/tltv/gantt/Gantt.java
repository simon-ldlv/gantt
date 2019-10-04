package org.tltv.gantt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.text.DateFormat;
import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.tltv.gantt.model.Resolution;
import org.tltv.gantt.model.Settings;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.HasSize;

public class Gantt extends GanttTemplate implements HasSize {

    protected static final String TZ_PATTERN = "^[A-Za-z]+ = (.*\"id\": \"([A-Za-z_/]+)\".*)$";
    protected static Set<String> timezoneIdCache;

    private LocalDateTime startDateTime;
    private LocalDateTime endDateTime;
    private Locale locale = Locale.getDefault();

    protected Map<String, String> timezoneJsonCache = new HashMap<String, String>();

    public Gantt() {
        super(new Settings());
        setWidth("100%");
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);

        updateLocaleData();
    }

    @Override
    public void setHeight(String height) {
        HasSize.super.setHeight(height);
        getModel().setHeight(height);
    }

    @Override
    public void setWidth(String width) {
        HasSize.super.setWidth(width);
        getModel().setWidth(width);
    }

    public LocalDateTime getStartDateTime() {
        return startDateTime;
    }

    /** Set inclusive start LocalDateTime. */
    public void setStartDateTime(LocalDateTime startDateTime) {
        this.startDateTime = startDateTime;
        resetStartDateTime();
        updateTimelineStartTimeDetails();
    }

    public LocalDateTime getEndDateTime() {
        return endDateTime;
    }

    /**
     * Set exclusive end LocalDateTime. Depending on the resolution, excludes
     * given day or hour.
     */
    public void setEndDateTime(LocalDateTime endDateTime) {
        this.endDateTime = endDateTime;
        resetEndDateTime();
        updateTimelineStartTimeDetails();
    }

    public void setLocale(Locale locale) {
        boolean changed = locale != getLocale();
        this.locale = locale;
        if (changed) {
            updateTimelineStartTimeDetails();
            updateLocale();
        }
    }

    @Override
    public Locale getLocale() {
        return locale;
    }

    public void setZoneId(ZoneId zoneId) {
        if (!getZoneId().equals(zoneId)) {
            this.zoneId = zoneId;
            resetStartDateTime();
            resetEndDateTime();
            updateTimelineStartTimeDetails();
        }
    }

    public void setResolution(Resolution resolution) {
        getModel().getSettings()
                .setResolution(Optional.ofNullable(resolution).map(Resolution::name).orElse(Resolution.Day.name()));
    }

    public Resolution getResolution() {
        return Resolution.valueOf(getModel().getSettings().getResolution());
    }

    void resetStartDateTime() {
        getModel().getSettings().setStartDate(toEpochMilli(resetTimeToMin(getStartDateTime())));
    }

    void resetEndDateTime() {
        getModel().getSettings().setEndDate(toEpochMilli(resetTimeToMax(getEndDateTime(), true)));
    }

    private void updateLocale() {
        Locale locale = getLocale();
        getModel().getSettings().setLocale(locale.toString());
        updateLocaleData();
    }

    private void updateLocaleData() {
        Calendar c = Calendar.getInstance(getLocale());
        c.set(2015, 0, 1);
        SimpleDateFormat shortMonthFormat = new SimpleDateFormat("MMM", getLocale());
        SimpleDateFormat longMonthFormat = new SimpleDateFormat("MMMM", getLocale());

        int monthsInYear = c.getMaximum(Calendar.MONTH) + 1;
        String[] monthNames = new String[monthsInYear];
        String[] shortMonthNames = new String[monthsInYear];
        for (int month = 0; month < monthsInYear; month++) {
            c.set(Calendar.MONTH, month);
            String shortMonth = shortMonthFormat.format(c.getTime());
            String longMonth = longMonthFormat.format(c.getTime());
            shortMonthNames[month] = shortMonth;
            monthNames[month] = longMonth;
        }
        getSettings().setLocaleMonthNames(Stream.of(monthNames).collect(Collectors.toList()));
        getSettings().setLocaleShortMonthNames(Stream.of(shortMonthNames).collect(Collectors.toList()));

        final DateFormatSymbols dfs = new DateFormatSymbols(getLocale());

        // Client expects 0 based indexing, DateFormatSymbols use 1 based
        String[] shortDayNames = new String[7];
        String[] dayNames = new String[7];
        String[] sDayNames = dfs.getShortWeekdays();
        String[] lDayNames = dfs.getWeekdays();
        for (int i = 0; i < 7; i++) {
            shortDayNames[i] = sDayNames[i + 1];
            dayNames[i] = lDayNames[i + 1];
        }
        getSettings().setLocaleShortDayNames(Stream.of(shortDayNames).collect(Collectors.toList()));
        getSettings().setLocaleDayNames(Stream.of(dayNames).collect(Collectors.toList()));

        // First day of week (0 = sunday, 1 = monday)
        final java.util.Calendar cal = new GregorianCalendar(getLocale());
        getSettings().setLocaleFirstDayOfWeek(cal.getFirstDayOfWeek() - 1);

        DateFormat timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT,
                getLocale());
        if (!(timeFormat instanceof SimpleDateFormat)) {
            // Unable to get default time pattern for locale
            timeFormat = new SimpleDateFormat();
        }
        final String timePattern = ((SimpleDateFormat) timeFormat).toPattern();
        getSettings().setLocaleTwelveHourClock(timePattern.indexOf("a") > -1);
    }

    Double toEpochMilli(LocalDateTime dateTime) {
        return Optional.ofNullable(dateTime).map(d -> (double) d.atZone(zoneId).toInstant().toEpochMilli())
                .orElse(null);
    }

    Double toUTCEpochMilli(LocalDateTime dateTime) {
        return Optional.ofNullable(dateTime)
                .map(d -> (double) d.atZone(zoneId).withZoneSameInstant(ZoneOffset.UTC).toInstant().toEpochMilli())
                .orElse(null);
    }

    LocalDateTime resetTimeToMin(LocalDateTime dateTime) {
        if (Objects.isNull(dateTime)) {
            return null;
        }
        if (Resolution.Hour.name().equals(getModel().getSettings().getResolution())) {
            return dateTime.truncatedTo(ChronoUnit.HOURS);
        }
        return dateTime.truncatedTo(ChronoUnit.DAYS);
    }

    LocalDateTime resetTimeToMax(LocalDateTime dateTime, boolean exclusive) {
        if (Objects.isNull(dateTime)) {
            return null;
        }
        if (Resolution.Hour.name().equals(getModel().getSettings().getResolution())) {
            if (exclusive) {
                dateTime = dateTime.minusHours(1);
            }
            return dateTime.plusHours(1).truncatedTo(ChronoUnit.HOURS).minusNanos(1);
        }
        if (exclusive) {
            dateTime = dateTime.minusDays(1);
        }
        return dateTime.plusDays(1).truncatedTo(ChronoUnit.DAYS).minusNanos(1);
    }

    private void updateTimelineStartTimeDetails() {
        if (Objects.isNull(startDateTime)) {
            return;
        }
        updateTimezoneOffsets();
        updateFirstHourOfRange();
        updateFirstDayOfRange();
    }

    private void updateTimezoneOffsets() {
        if (!Objects.isNull(getStartDateTime())) {
            resetStartDateTime();
        }
        if (!Objects.isNull(getEndDateTime())) {
            resetEndDateTime();
        }
        getModel().getSettings().setTimeZoneId(getZoneId().getId());
        getModel().getSettings().setTimeZoneJson(getTimeZoneJson(getZoneId().getId()));
    }

    private void updateFirstHourOfRange() {
        getModel().getSettings().setFirstHourOfRange(getStartDateTime().getHour());
    }

    private void updateFirstDayOfRange() {
        getModel().getSettings().setFirstDayOfRange(translateDayOfWeek(getStartDateTime().getDayOfWeek()));
    }

    private BufferedReader createTimeZonePropertiesReader(InputStream inputStream) {
        InputStreamReader isr = new InputStreamReader(inputStream);
        return new BufferedReader(isr);
    }

    private String readTimeZoneJson(String id, BufferedReader reader) throws IOException {
        for (String line; (line = reader.readLine()) != null;) {
            Pattern pattern = Pattern.compile(TZ_PATTERN);
            Matcher matcher = pattern.matcher(line);
            if (matcher.matches()) {
                if (id.equals(matcher.group(2))) {
                    String json = matcher.group(1);
                    timezoneJsonCache.put(id, json);
                    return json;
                }
            }
        }
        return null;
    }

    private static Set<String> readSupportedTimezones(BufferedReader reader) throws IOException {
        HashSet<String> ids = new HashSet<>();
        for (String line; (line = reader.readLine()) != null;) {
            Pattern pattern = Pattern.compile(TZ_PATTERN);
            Matcher matcher = pattern.matcher(line);
            if (matcher.matches()) {
                ids.add(matcher.group(2));
            }
        }
        return ids;
    }

    protected InputStream createTimeZonePropertiesInputStream(String propertiesFileName) {
        // read time zone json from TimeZoneConstants.properties.
        return Gantt.class.getResourceAsStream(propertiesFileName);
    }

    protected String getTimeZoneJson(String id) {
        if (timezoneJsonCache.containsKey(id)) {
            return timezoneJsonCache.get(id);
        }
        String propertiesFileName = "TimeZoneConstants.properties";
        // read time zone json from TimeZoneConstants.properties.
        InputStream is = createTimeZonePropertiesInputStream(propertiesFileName);
        BufferedReader reader = createTimeZonePropertiesReader(is);
        try {
            String json = readTimeZoneJson(id, reader);
            if (json != null) {
                return json;
            }
            throw new IllegalArgumentException(String.format("Time zone %s not found in %s", id, propertiesFileName));
        } catch (IOException e) {
            throw new RuntimeException(String.format("Failed to read time zone from %s", propertiesFileName), e);
        } finally {
            closeReader("Failed to close time-zone resource stream reader.", reader);
            closeStream("Failed to close time-zone resource stream.", is);
        }
    }

    private void closeStream(String failMsg, InputStream is) {
        try {
            is.close();
        } catch (IOException e) {
            throw new RuntimeException(failMsg, e);
        }
    }

    private void closeReader(String failMsg, Reader r) {
        try {
            r.close();
        } catch (IOException e) {
            throw new RuntimeException(failMsg, e);
        }
    }

    private int translateDayOfWeek(DayOfWeek dow) {
        // Why? Compare current API DayOfWeek.getValue() vs. old API with
        // Calendar.get(Calendar.DAY_OF_WEEK)
        return (DayOfWeek.SUNDAY.equals(dow)) ? 1 : dow.getValue() + 1;
    }

    /**
     * Get all supported Time zone identifiers (like "Europe/Rome"). Reads them
     * by default from TimeZoneConstants.properties. First call will cache
     * result, and following calls will return cached set.
     */
    public static Set<String> getSupportedTimeZoneIDs() {
        if (timezoneIdCache != null) {
            return Collections.unmodifiableSet(timezoneIdCache);
        }
        timezoneIdCache = new HashSet<>();

        String propertiesFileName = "TimeZoneConstants.properties";
        // read time zones from TimeZoneConstants.properties.
        InputStream is = Gantt.class.getResourceAsStream(propertiesFileName);
        InputStreamReader isr = new InputStreamReader(is);
        BufferedReader reader = new BufferedReader(isr);
        try {
            timezoneIdCache = readSupportedTimezones(reader);
            return timezoneIdCache;
        } catch (IOException e) {
            throw new RuntimeException(String.format("Failed to read time zone from %s", propertiesFileName), e);
        } finally {
            try {
                reader.close();
                isr.close();
                is.close();
            } catch (IOException e) {
                throw new RuntimeException("Failed to close open resources.", e);
            }
        }
    }
}
