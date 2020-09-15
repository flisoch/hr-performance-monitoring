package com.example.monitoring.controller;

import com.example.monitoring.MonitoringApplication;
import com.example.monitoring.domain.Assessment;
import com.example.monitoring.domain.AssessmentCriteria;
import com.example.monitoring.domain.Performer;
import com.example.monitoring.service.StudentUserService;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeRequestUrl;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.annotation.PostConstruct;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.time.LocalDateTime.now;


@Controller
public class SheetParser {

    private static final String APPLICATION_NAME = "HR monitoring app stats calculator";
    @Value("${google.spreadsheet.id}")
    private String SPREADSHEET_ID;
    private static final String USER_IDENTIFIER_KEY = "MY_DUMMY_USER";
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final String TOKENS_DIRECTORY_PATH = "tokens";
    private static HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
    private final String CALLBACK_URI = "http://localhost:8080/oauth";

    private static final List<String> SCOPES = Collections.singletonList(SheetsScopes.SPREADSHEETS);
    private static final String CREDENTIALS_FILE_PATH = "/client_secret.json";

    @Autowired
    StudentUserService studentUserService;
    private GoogleAuthorizationCodeFlow flow;

    @PostConstruct
    public void init() throws Exception {
        GoogleClientSecrets secrets = GoogleClientSecrets.load(JSON_FACTORY,
                new InputStreamReader(MonitoringApplication.class.getResourceAsStream(CREDENTIALS_FILE_PATH)));
        flow = new GoogleAuthorizationCodeFlow.Builder(HTTP_TRANSPORT, JSON_FACTORY, secrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new File(TOKENS_DIRECTORY_PATH))).build();

    }


    @GetMapping(value = {"/signin"})
    public void doGoogleSignIn(HttpServletResponse response) throws Exception {
        GoogleAuthorizationCodeRequestUrl url = flow.newAuthorizationUrl();
        String redirectURL = url.setRedirectUri(CALLBACK_URI).setAccessType("offline").build();
        response.sendRedirect(redirectURL);
    }

    @GetMapping(value = {"/oauth"})
    public ResponseEntity saveAuthorizationCode(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String code = request.getParameter("code");
        if (code != null) {
            if (studentUserService.userAuthorized(request)) {
                return ResponseEntity.ok("You are already authorized!");

            } else {
                Cookie cookie = studentUserService.saveUserCookie();
                saveToken(code, cookie.getValue());
                response.addCookie(cookie);
            }
            return (ResponseEntity) ResponseEntity.ok("token is saved, sent you a cookie! <a href=\"http://localhost:8080/sheet\">Continue</a>");
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("No code received from google oauth");
    }

    private void saveToken(String code, String userIdentifierKey) throws Exception {
        GoogleTokenResponse response = flow.newTokenRequest(code).setRedirectUri(CALLBACK_URI).execute();
        flow.createAndStoreCredential(response, userIdentifierKey);

    }

    private Credential getCredentials(HttpServletRequest request) throws IOException {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            Optional<Cookie> userId = Arrays.stream(cookies).filter(cookie -> cookie.getName().equals("userId")).findAny();
            if (userId.isPresent()) {
                return flow.loadCredential(userId.get().getValue());
            }
        }
        return null;
    }

    @GetMapping("/sheet")
    public ResponseEntity sheetData(HttpServletRequest request, @RequestParam(required = false) String tabName) throws GeneralSecurityException, IOException {
        Credential credentials = getCredentials(request);
        if (credentials == null) {
            return ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .body("The request is missing a valid API key.\n Please authorize in <a href=\"http://localhost:8080/signin\">link</a>");
        }
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        Sheets service = new Sheets.Builder(HTTP_TRANSPORT, JSON_FACTORY, credentials)
                .setApplicationName(APPLICATION_NAME)
                .build();


        List<Performer> performers = getPerformers(service);
        List<Performer> averageAssessments = getAverageAssessments(performers, service);
        Sheet sheet = createReportSheet(service);
        String columnsRange = putColumnNamesIntoReportSheet(service, sheet);
        putCalculatedValuesIntoReportSheet(averageAssessments, service, sheet);
        setColumnSizeFitData(columnsRange, service, sheet);
        return ResponseEntity.ok(averageAssessments);
    }

    private void setColumnSizeFitData(String columnsRange, Sheets service, Sheet sheet) throws IOException {
        AutoResizeDimensionsRequest request = new AutoResizeDimensionsRequest();
        DimensionRange dimensionRange = new DimensionRange();
        dimensionRange.setSheetId(sheet.getProperties().getSheetId());
        dimensionRange.setDimension("COLUMNS");
        GridRange gridRange = getGridRangeFromA1Notation(columnsRange, sheet);
        dimensionRange.setStartIndex(gridRange.getStartColumnIndex());
        dimensionRange.setEndIndex(gridRange.getEndColumnIndex());
        request.setDimensions(dimensionRange);
        BatchUpdateSpreadsheetRequest batchUpdateRequest = new BatchUpdateSpreadsheetRequest();
        Request r = new Request();
        r.setAutoResizeDimensions(request);
        batchUpdateRequest.setRequests(Collections.singletonList(r));
        BatchUpdateSpreadsheetResponse response = service.spreadsheets().batchUpdate(SPREADSHEET_ID, batchUpdateRequest).execute();
        List<Response> replies = response.getReplies();
    }

    private String putColumnNamesIntoReportSheet(Sheets service, Sheet sheet) throws IOException {
        String sheetTitle = sheet.getProperties().getTitle();
        ValueRange columnNames = createColumns(sheetTitle);
        Sheets.Spreadsheets.Values.Append request = service.spreadsheets().values()
                .append(SPREADSHEET_ID, sheetTitle + "!A:E", columnNames);
        request.setValueInputOption("RAW");
        AppendValuesResponse response = request.execute();
        String updatedRange = response.getUpdates().getUpdatedRange();
        updateBorders(updatedRange, service, sheet);
        return updatedRange;
    }

    private void updateBorders(String updatedRange, Sheets service, Sheet sheet) throws IOException {
        Border border = new Border();
        border.setStyle("SOLID_MEDIUM");
        Color color = new Color();
        color.setRed(255F);
        color.setGreen(255F);
        color.setBlue(255F);
        color.setAlpha(1F);
        border.setColor(color);
        UpdateBordersRequest bordersRequest = new UpdateBordersRequest();
        bordersRequest.setBottom(border);
        bordersRequest.setRight(border.clone());
        bordersRequest.setLeft(border.clone());
        GridRange gridRange = getGridRangeFromA1Notation(updatedRange, sheet);
        bordersRequest.setRange(gridRange);
        Request request = new Request();
        request.setUpdateBorders(bordersRequest);
        BatchUpdateSpreadsheetRequest updateRequest = new BatchUpdateSpreadsheetRequest();
        updateRequest.setRequests(Collections.singletonList(request));

        service.spreadsheets().batchUpdate(SPREADSHEET_ID, updateRequest).execute();

    }

    private GridRange getGridRangeFromA1Notation(String updatedRange, Sheet sheet) throws IOException {
        GridRange gridRange = new GridRange();
        Integer sheetId = sheet.getProperties().getSheetId();
        gridRange.setSheetId(sheetId);
        setIndexes(gridRange, updatedRange);
        return gridRange;
    }

    private void setIndexes(GridRange gridRange, String updatedRange) {
        Pattern pattern = Pattern.compile("(^.+)!(.+):(.+$)");
        Matcher matcher = pattern.matcher(updatedRange);
        matcher.find();
        String start = matcher.group(2);
        String end = matcher.group(3);
        gridRange.setStartRowIndex(Integer.parseInt(start.substring(1)) - 1);
        gridRange.setStartColumnIndex(getNumberForChar(start.charAt(0)) - 1);
        gridRange.setEndRowIndex(Integer.parseInt(end.substring(1)));
        gridRange.setEndColumnIndex(getNumberForChar(end.charAt(0)));
        System.out.println(gridRange);
    }

    private String getCharForNumber(int i) {
        return i > 0 && i < 27 ? String.valueOf((char) (i + 64)) : null;
    }

    private int getNumberForChar(char c) {
        int num = c;
        num = num - 64;
        return num;
    }

    private void putCalculatedValuesIntoReportSheet(List<Performer> averageAssessments, Sheets service, Sheet sheet) throws IOException {
        String sheetTitle = sheet.getProperties().getTitle();
        List<ValueRange> commandsAssessments = assessmentsByTeams(averageAssessments, sheetTitle);
        List<ValueRange> values = new ArrayList<>();
        values.addAll(commandsAssessments);

        for (ValueRange valueRange : values) {
            Sheets.Spreadsheets.Values.Append request = service.spreadsheets().values()
                    .append(SPREADSHEET_ID, sheetTitle + "!A:E", valueRange);
            request.setValueInputOption("RAW");
            request.setInsertDataOption("INSERT_ROWS");
            AppendValuesResponse response = request.execute();
            updateBorders(response.getUpdates().getUpdatedRange(), service, sheet);
        }

    }

    private List<ValueRange> assessmentsByTeams(List<Performer> averageAssessments, String sheetName) {
        HashMap<String, List<Performer>> assessmentsByTeams = new HashMap<>();
        Set<Map.Entry<String, List<Performer>>> assessments = assessmentsByTeams.entrySet();

        for (Performer performer : averageAssessments) {
            String teamName = performer.getCompetencyAssessment().getTeam().getName();
            if (assessmentsByTeams.containsKey(teamName)) {
                List<Performer> performers = assessmentsByTeams.get(teamName);
                performers.add(performer);
                assessmentsByTeams.replace(teamName, performers);
            } else {
                List<Performer> performers = new ArrayList<>();
                performers.add(performer);
                assessmentsByTeams.put(teamName, performers);
            }
        }
        Set<String> teams = assessmentsByTeams.keySet();
        List<ValueRange> result = new ArrayList<>();
        for (String team : teams) {
            ValueRange teamStats = getTeamStats(assessmentsByTeams.get(team));
            teamStats.setRange(sheetName + "!A:E");
            teamStats.setMajorDimension("ROWS");
            result.add(teamStats);
        }
        return result;
    }

    private ValueRange getTeamStats(List<Performer> assessmentsByTeams) {
        ValueRange valueRange = new ValueRange();
        String teamName = assessmentsByTeams.get(0).getCompetencyAssessment().getTeam().getName();
        String competencyLower = "";
        String competencyMeet = "";
        String competencyHigher = "";
        String efficiencyLower = "";
        String efficiencyMeet = "";
        String efficiencyHigher = "";
        for (Performer performer : assessmentsByTeams) {

            switch (performer.getCompetencyAssessment().getAssessmentValue()) {
                case LOWER:
                    competencyLower += performer.getName() + "\n";
                    break;
                case MEET:
                    competencyMeet += performer.getName() + "\n";
                    break;
                case HIGHER:
                    competencyHigher += performer.getName() + "\n";
            }
            switch (performer.getEfficiencyAssessments().getAssessmentValue()) {
                case LOWER:
                    efficiencyLower += performer.getName() + "\n";
                    break;
                case MEET:
                    efficiencyMeet += performer.getName() + "\n";
                    break;
                case HIGHER:
                    efficiencyHigher += performer.getName() + "\n";
            }
        }
        List<Object> competencyValues = Arrays.asList(
                teamName,
                "компетентность",
                competencyLower.equals("") ? "-" : competencyLower,
                competencyMeet.equals("") ? "-" : competencyMeet,
                competencyHigher.equals("") ? "-" : competencyHigher);
        List<Object> performanceValues = Arrays.asList(
                teamName,
                "результативность",
                efficiencyLower.equals("") ? "-" : efficiencyLower,
                efficiencyMeet.equals("") ? "-" : efficiencyMeet,
                efficiencyHigher.equals("") ? "-" : efficiencyHigher);
        List<List<Object>> totalValues = Arrays.asList(competencyValues, performanceValues);
        valueRange.setValues(totalValues);
        return valueRange;
    }

    private ValueRange createColumns(String sheetName) {
        List<List<Object>> lists = Arrays.asList(Collections.singletonList("команда"),
                Collections.singletonList("критерий оценивания"),
                Collections.singletonList("ниже"),
                Collections.singletonList("соответствует"),
                Collections.singletonList("выше"));

        ValueRange columns = new ValueRange();
        columns.setRange(sheetName + "!A:E");
        columns.setValues(lists);
        columns.setMajorDimension("COLUMNS");
        return columns;
    }

    private Sheet createReportSheet(Sheets service) throws IOException {
        AddSheetRequest addSheetRequest = new AddSheetRequest();
        SheetProperties sheetProperties = new SheetProperties();
        String title = getReportTitle();
        sheetProperties.setTitle(title);
        addSheetRequest.setProperties(sheetProperties);
        Request request = new Request().setAddSheet(addSheetRequest);
        List<Request> requests = new ArrayList<>();
        requests.add(request);
        BatchUpdateSpreadsheetRequest requestBody = new BatchUpdateSpreadsheetRequest();
        requestBody.setRequests(requests);
        requestBody.setIncludeSpreadsheetInResponse(true);
        BatchUpdateSpreadsheetResponse response = service.spreadsheets().batchUpdate(SPREADSHEET_ID, requestBody).execute();
        List<Sheet> sheets = response.getUpdatedSpreadsheet().getSheets();
        Sheet newSheet = sheets.stream().filter(sheet -> sheet.getProperties().getTitle().equals(title)).findAny().get();
        return newSheet;
    }

    private String getReportTitle() {
        LocalDateTime now = now();
        String title = "report " + now.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + now.getSecond();
        return title;
    }

    private List<Performer> getPerformers(Sheets service) throws IOException {
        List<Performer> performers = new ArrayList<>();
        String range = "PM!A2:A10";
        List<List<Object>> valueRanges = getValues(range, service);
        for (List<Object> row : valueRanges) {
            Performer performer = Performer.buildFromSheetRow(row);
            if (performers.stream().anyMatch(p -> p.getName().equals(performer.getName()))) {
                continue;
            }
            performers.add(performer);
        }
        return performers;
    }

    private List<Performer> getAverageAssessments(List<Performer> performers, Sheets service) throws IOException {
        List<Assessment> pmAssessments = getSheetAssessments("TLead", service);
        List<Assessment> tLeadAssessments = getSheetAssessments("PM", service);
        for (Performer performer : performers) {
            Assessment pmAssessment = getPerformerAssessment(performer, pmAssessments, AssessmentCriteria.COMPETENCY);
            Assessment tLeadAssessment = getPerformerAssessment(performer, tLeadAssessments, AssessmentCriteria.COMPETENCY);
            Assessment average = Assessment.average(pmAssessment, tLeadAssessment);
            performer.setCompetencyAssessment(average);

            Assessment pmPerformanceAssessment = getPerformerAssessment(performer, pmAssessments, AssessmentCriteria.PERFORMANCE);
            Assessment tLeadPerformanceAssessment = getPerformerAssessment(performer, tLeadAssessments, AssessmentCriteria.PERFORMANCE);
            Assessment performanceAverage = Assessment.average(pmPerformanceAssessment, tLeadPerformanceAssessment);
            performer.setEfficiencyAssessments(performanceAverage);
        }
        return performers;
    }

    private Assessment getPerformerAssessment(Performer performer, List<Assessment> pmAssessments, AssessmentCriteria criteria) {
        return pmAssessments.stream()
                .filter(assessment -> assessment.getPerformer().getName().equals(performer.getName()))
                .filter(assessment -> assessment.getAssessmentCriteria().equals(criteria))
                .findAny()
                .get();
    }

    private List<Assessment> getSheetAssessments(String sheetName, Sheets service) throws IOException {
        List<Assessment> assessments = new ArrayList<>();
        String range = sheetName + "!A2:E10";
        List<List<Object>> valueRanges = getValues(range, service);
        for (List<Object> row : valueRanges) {
            Assessment assessment = Assessment.buildFromSheetRow(row);
            assessments.add(assessment);
        }
        return assessments;
    }

    private List<List<Object>> getValues(String range, Sheets service) throws IOException {
        ValueRange request =
                service.spreadsheets().values().get(SPREADSHEET_ID, range).execute();
        return request.getValues();
    }
}
