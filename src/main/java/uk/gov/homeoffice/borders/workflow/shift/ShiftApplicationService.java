package uk.gov.homeoffice.borders.workflow.shift;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.runtime.VariableInstance;
import org.camunda.bpm.engine.variable.Variables;
import org.camunda.bpm.engine.variable.value.ObjectValue;
import org.camunda.spin.Spin;
import org.camunda.spin.impl.json.jackson.format.JacksonJsonDataFormat;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;
import uk.gov.homeoffice.borders.workflow.PlatformDataUrlBuilder;
import uk.gov.homeoffice.borders.workflow.config.PlatformDataBean;
import uk.gov.homeoffice.borders.workflow.exception.ResourceNotFound;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Application Service responsible for dealing with the internal
 * workflow for creating an active shift
 */

@Slf4j
@AllArgsConstructor(onConstructor = @__(@Autowired))
public class ShiftApplicationService {

    private RuntimeService runtimeService;

    private RestTemplate restTemplate;

    private PlatformDataUrlBuilder platformDataUrlBuilder;

    private PlatformDataBean platformDataBean;
    private JacksonJsonDataFormat formatter;

    /**
     * Start a shift workflow
     *
     * @param shiftInfo Contains information about a shift
     * @return processInstance created
     * @see ShiftInfo
     * @see ProcessInstance
     */
    public ProcessInstance startShift(@NotNull @Valid ShiftInfo shiftInfo) {

        String email = shiftInfo.getEmail();
        log.info("Starting a request to start a shift for '{}'", email);

        deleteShift(email, "new-shift");

        setEndTime(shiftInfo);

        Spin<?> shiftVariableObject = Spin.S(shiftInfo, formatter);

        Map<String, Object> variables = new HashMap<>();
        variables.put("shiftInfo", shiftVariableObject);
        variables.put("type", "non-notification");

        ProcessInstance processInstance = runtimeService
                .startProcessInstanceByKey("activate-shift", email, variables);
        log.info("Shift process for '{}' has started '{}'", email, processInstance.getProcessInstanceId());
        return processInstance;
    }

    private void setEndTime(ShiftInfo shiftInfo) {
        Integer shiftHours = shiftInfo.getShiftHours();
        Integer shiftMinutes = shiftInfo.getShiftMinutes();
        Date startTime = new DateTime(shiftInfo.getStartDateTime())
                .withZone(org.joda.time.DateTimeZone.UTC).toDate();
        shiftInfo.setEndDateTime(new DateTime(startTime)
                .plusHours(shiftHours)
                .plusMinutes(shiftMinutes)
                .withZone(org.joda.time.DateTimeZone.UTC)
                .toDate());
    }

    /**
     * Deletes a workflow with the given email
     *
     * @param email        identifies the shift that needs to be deleted
     * @param deleteReason This is required and explains why the workflow was cancelled.
     * @see ProcessInstance
     */
    @CacheEvict(cacheNames = {"shifts"}, key = "#email")
    public void deleteShift(@NotNull String email, @NotNull String deleteReason) {
        List<ProcessInstance> instances = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(email).list();
        if (!CollectionUtils.isEmpty(instances)) {
            List<String> ids = instances.stream()
                    .map(ProcessInstance::getProcessInstanceId)
                    .collect(Collectors.toList());
            List<String> shifts = runtimeService.createVariableInstanceQuery()
                    .variableName("shiftId")
                    .processInstanceIdIn(ids.toArray(new String[]{})).list()
                    .stream()
                    .map(v -> (String) v.getValue())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());


            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.set("Authorization", "Bearer " + platformDataBean.getToken());

            shifts.stream().forEach(id -> {
                restTemplate.exchange(platformDataUrlBuilder.shiftUrlById(id),
                        HttpMethod.DELETE, new HttpEntity<>(httpHeaders), String.class);
                log.info("active shift '{}' deleted from store...", id);
            });

            runtimeService.deleteProcessInstances(ids, deleteReason, false, true);

            log.info("Shift deleted for '{}'", email);
        }

    }


    /**
     * Get shift info for given email
     *
     * @param email
     * @return shiftInfo.
     * @throws ResourceNotFound shift info cannot be found
     * @see ShiftInfo
     */
    public ShiftInfo getShiftInfo(@NotNull String email) {
        ProcessInstance shift = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(email)
                .singleResult();

        if (shift == null) {
            throw new ResourceNotFound("Shift detail not found for '" + email + "'");
        }

        VariableInstance variableInstance = runtimeService.createVariableInstanceQuery()
                .processInstanceIdIn(shift.getProcessInstanceId())
                .variableName("shiftInfo").singleResult();

        if (variableInstance != null) {
            ShiftInfo shiftInfo = Spin.S(variableInstance.getValue(), formatter).mapTo(ShiftInfo.class);
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.set("Accept", "application/vnd.pgrst.object+json");

            HttpEntity<?> entity = new HttpEntity<>(httpHeaders);

            ResponseEntity<Map<String,String>> response = restTemplate
                    .exchange(platformDataUrlBuilder.getLocation(shiftInfo.getCurrentLocationId()),
                            HttpMethod.GET,
                            entity,
                            new ParameterizedTypeReference<Map<String,String>>() {}
                    );

            if (response.getStatusCode().is2xxSuccessful()) {
                String locationName = Optional.ofNullable(response.getBody())
                        .orElse(Collections.singletonMap("locationname", "Unknown")).get("locationname");
                shiftInfo.setCurrentLocationName(locationName);
            }

            return shiftInfo;
        }

        throw new ResourceNotFound("Shift data could not be found");
    }


}
