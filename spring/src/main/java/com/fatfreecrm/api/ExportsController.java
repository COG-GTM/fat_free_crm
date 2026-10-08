package com.fatfreecrm.api;

import com.fatfreecrm.api.support.CrmApiRequestSupport;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.service.export.ExportService;
import com.fatfreecrm.service.export.ExportService.ExportDocument;
import com.fatfreecrm.service.export.ExportService.Format;
import com.fatfreecrm.service.json.RailsResource;
import com.fatfreecrm.service.json.RailsResources;
import com.fatfreecrm.service.read.TaskReadService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * AB-273 CSV / SpreadsheetML exports on the AB-270 list paths. Negotiated by {@code Accept} or {@code ?format=}
 * via {@code produces}, so the JSON read controllers stay untouched; {@code .csv} / {@code .xls} suffixes mirror
 * the Rails URLs the gateway forwards.
 */
@RestController
@RequestMapping("/api/v1")
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed collaborators are intentionally retained by this controller."
)
public class ExportsController {

    static final String CSV = "text/csv";
    static final String XLS = "application/vnd.ms-excel";
    static final String RAILS_XLS = "application/vnd.msexcel";

    private final ExportService exportService;
    private final TaskReadService taskReadService;
    private final RailsResources railsResources;
    private final CrmApiRequestSupport requestSupport;
    private final ContentNegotiationManager contentNegotiationManager;

    public ExportsController(
        ExportService exportService,
        TaskReadService taskReadService,
        RailsResources railsResources,
        CrmApiRequestSupport requestSupport,
        ContentNegotiationManager contentNegotiationManager
    ) {
        this.exportService = exportService;
        this.taskReadService = taskReadService;
        this.railsResources = railsResources;
        this.requestSupport = requestSupport;
        this.contentNegotiationManager = contentNegotiationManager;
    }

    @GetMapping(
        path = {"/accounts", "/campaigns", "/contacts", "/leads", "/opportunities", "/tasks", "/activities"},
        produces = {CSV, XLS, RAILS_XLS})
    @Operation(summary = "Export a list as CSV or SpreadsheetML XLS",
        description = "Selected by Accept: text/csv | application/vnd.ms-excel, or ?format=csv|xls. Full list, "
            + "same filters, search and order as the JSON list.")
    public ResponseEntity<byte[]> negotiated(
        Authentication authentication,
        HttpServletRequest request,
        @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params
    ) throws HttpMediaTypeNotAcceptableException {
        return export(authentication, request.getRequestURI(), negotiatedFormat(request), params);
    }

    @GetMapping(path = {
        "/accounts.csv", "/campaigns.csv", "/contacts.csv", "/leads.csv", "/opportunities.csv", "/tasks.csv",
        "/activities.csv", "/accounts.xls", "/campaigns.xls", "/contacts.xls", "/leads.xls", "/opportunities.xls",
        "/tasks.xls", "/activities.xls"})
    @Operation(summary = "Export a list as CSV or SpreadsheetML XLS (Rails format suffix)")
    public ResponseEntity<byte[]> suffixed(
        Authentication authentication,
        HttpServletRequest request,
        @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params
    ) {
        String path = request.getRequestURI();
        Format format = path.endsWith(".xls") ? Format.XLS : Format.CSV;
        return export(authentication, path.substring(0, path.length() - 4), format, params);
    }

    private ResponseEntity<byte[]> export(
        Authentication authentication,
        String path,
        Format format,
        MultiValueMap<String, String> params
    ) {
        AuthenticatedUser user = requestSupport.authenticatedUser(authentication);
        String family = path.substring(path.lastIndexOf('/') + 1);
        ExportDocument document = switch (family) {
            case "tasks" -> exportService.tasks(user, params.getFirst("view"),
                taskReadService.zone(params.getFirst("timeZone")), format);
            case "activities" -> exportService.activities(user, params, format);
            default -> {
                RailsResource resource = resource(family);
                yield exportService.list(user, resource,
                    requestSupport.listQuery(user, resource, params, params.getFirst(filterParameter(family))),
                    format);
            }
        };
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, document.contentType());
        if (document.contentDisposition() != null) {
            response.header(HttpHeaders.CONTENT_DISPOSITION, document.contentDisposition());
        }
        return response.body(document.body());
    }

    private RailsResource resource(String family) {
        return switch (family) {
            case "accounts" -> railsResources.account;
            case "campaigns" -> railsResources.campaign;
            case "contacts" -> railsResources.contact;
            case "leads" -> railsResources.lead;
            default -> railsResources.opportunity;
        };
    }

    /** The AB-270 list filter parameter standing in for Rails' session {@code <controller>_filter}. */
    private static String filterParameter(String family) {
        return switch (family) {
            case "accounts" -> "category";
            case "campaigns", "leads" -> "status";
            case "opportunities" -> "stage";
            default -> "filter";
        };
    }

    private Format negotiatedFormat(HttpServletRequest request) throws HttpMediaTypeNotAcceptableException {
        List<MediaType> requested = contentNegotiationManager.resolveMediaTypes(new ServletWebRequest(request));
        for (MediaType type : requested) {
            if (type.isCompatibleWith(MediaType.parseMediaType(CSV)) && !type.isWildcardSubtype()) {
                return Format.CSV;
            }
            if (!type.isWildcardSubtype() && (type.isCompatibleWith(MediaType.parseMediaType(XLS))
                    || type.isCompatibleWith(MediaType.parseMediaType(RAILS_XLS)))) {
                return Format.XLS;
            }
        }
        return Format.CSV;
    }
}
