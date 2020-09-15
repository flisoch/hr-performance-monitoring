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
import java.util.stream.Collectors;

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
        String sheetName = createReportSheet(service);
        Integer updatedSheetsCount = putCalculatedValuesIntoReportSheet(averageAssessments, service, sheetName);
        return ResponseEntity.ok(updatedSheetsCount);
    }

    private Integer putCalculatedValuesIntoReportSheet(List<Performer> averageAssessments, Sheets service, String sheetName) throws IOException {
        ValueRange columnNames = createColumns(sheetName);
        List<ValueRange> commandsAssessments = assessmentsByTeams(averageAssessments, sheetName);
        List<ValueRange> values = new ArrayList<>();
        values.add(columnNames);
        values.addAll(commandsAssessments);
        BatchUpdateValuesRequest requestBody = new BatchUpdateValuesRequest();
        requestBody.setValueInputOption("INPUT_VALUE_OPTION_UNSPECIFIED");
        requestBody.setValueInputOption("RAW");
        requestBody.setData(values);

        Sheets.Spreadsheets.Values.BatchUpdate request =
                service.spreadsheets().values().batchUpdate(SPREADSHEET_ID, requestBody);

        BatchUpdateValuesResponse response = request.execute();
        return response.getTotalUpdatedSheets();
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
            teamStats.setMajorDimension("COLUMNS");
            result.add(teamStats);
        }
        return result;
    }

    private ValueRange getTeamStats(List<Performer> assessmentsByTeams) {
        ValueRange valueRange = new ValueRange();
        String teamName = "";
        String competencyLower = "";
        String competencyMeet = "";
        String competencyHigher = "";
        String efficiencyLower = "";
        String efficiencyMeet = "";
        String efficiencyHigher = "";
        for (Performer performer : assessmentsByTeams) {

            switch (performer.getCompetencyAssessment().getAssessmentValue()) {
                case LOWER:
                    competencyLower += performer.getName() + " ";
                    break;
                case MEET:
                    competencyMeet += performer.getName() + " ";
                    break;
                case HIGHER:
                    competencyHigher += performer.getName() + " ";
            }
            switch (performer.getEfficiencyAssessments().getAssessmentValue()) {
                case LOWER:
                    efficiencyLower += performer.getName() + " ";
                    break;
                case MEET:
                    efficiencyMeet += performer.getName() + " ";
                    break;
                case HIGHER:
                    efficiencyHigher += performer.getName() + " ";
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
        columns.setRange(sheetName + "!A1:E1");
        columns.setValues(lists);
        columns.setMajorDimension("COLUMNS");
        return columns;
    }

    private String createReportSheet(Sheets service) throws IOException {
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
        BatchUpdateSpreadsheetResponse response = service.spreadsheets().batchUpdate(SPREADSHEET_ID, requestBody).execute();
        return title;
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

    private List<String> getAssessmentRanges(String pmSheetName) {
        List<String> ranges = new ArrayList<>();
        String[] ranges1 = new String[]{"A2:A7", "B2:B7", "C2:C7", "D2:D7"};
        return Arrays.stream(ranges1).map(s -> pmSheetName + s).collect(Collectors.toList());
    }

}
