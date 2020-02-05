package uk.gov.homeoffice.borders.workflow.cases;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.history.HistoricProcessInstanceQuery;
import org.camunda.bpm.engine.rest.dto.history.HistoricProcessInstanceDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;
import uk.gov.homeoffice.borders.workflow.PageHelper;
import uk.gov.homeoffice.borders.workflow.identity.PlatformUser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.lang.String.format;

@Slf4j
@Service
@AllArgsConstructor(onConstructor = @__(@Autowired))
public class CasesApplicationService {

    private HistoryService historyService;
    private ApplicationEventPublisher applicationEventPublisher;
    private AmazonS3 amazonS3Client;

    private AWSConfig awsConfig;

    private static final PageHelper PAGE_HELPER = new PageHelper();

    public Page<Case> findBy(String businessKey, Pageable pageable) {
        HistoricProcessInstanceQuery query = historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKeyLike(businessKey);

        long totalResults = query.count();

        List<HistoricProcessInstance> historicProcessInstances = query
                .listPage(PAGE_HELPER.calculatePageNumber(pageable)
                        , pageable.getPageSize());

        Map<String, List<HistoricProcessInstance>> groupedByBusinessKey = historicProcessInstances
                .stream().collect(Collectors.groupingBy(HistoricProcessInstance::getBusinessKey));

        List<Case> cases = groupedByBusinessKey.keySet().stream().map((key) -> {
            Case caseDto = new Case();
            caseDto.setBusinessKey(key);
            List<HistoricProcessInstance> instances = groupedByBusinessKey.get(key);
            caseDto.setAssociatedProcessInstances(instances
                    .stream()
                    .map(HistoricProcessInstanceDto::fromHistoricProcessInstance).collect(Collectors.toList()));
            return caseDto;
        }).collect(Collectors.toList());

        log.info("Number of cases returned for '{}' is '{}'", businessKey, cases.size());
        return new PageImpl<>(cases, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()), totalResults);

    }


    public CaseDetail getByKey(String businessKey, PlatformUser platformUser) {

        log.info("Beginning case detail fetch");
        StopWatch stopWatch = new StopWatch();

        stopWatch.start();
        CaseDetail caseDetail = new CaseDetail();
        caseDetail.setBusinessKey(businessKey);


        ObjectListing objectListing = amazonS3Client
                .listObjects(awsConfig.getCaseBucketName(), format("%s/", businessKey));


        List<ObjectMetadata> metadata = new ArrayList<>();
        objectListing.getObjectSummaries().forEach(summary -> {
            ObjectMetadata objectMetadata = amazonS3Client
                    .getObjectMetadata(new GetObjectMetadataRequest(summary.getBucketName(), summary.getKey()));
            objectMetadata.addUserMetadata("key", summary.getKey());
            metadata.add(objectMetadata);

        });


        Map<String, List<ObjectMetadata>> byProcessDefinitionIds = metadata
                .stream()
                .collect(Collectors.groupingBy(meta -> meta.getUserMetaDataOf("processDefinitionId")));


        List<HistoricProcessInstance> processInstances = historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).list();

        List<CaseDetail.ProcessInstanceReference> instanceReferences = processInstances
                .stream()
                .map(historicProcessInstance -> {
            CaseDetail.ProcessInstanceReference reference = new CaseDetail.ProcessInstanceReference();
            reference.setDefinitionId(historicProcessInstance.getProcessDefinitionId());
            reference.setName(historicProcessInstance.getProcessDefinitionName());
            reference.setKey(historicProcessInstance.getProcessDefinitionKey());
            reference.setStartDate(historicProcessInstance.getStartTime());
            reference.setEndDate(historicProcessInstance.getEndTime());
            reference.setFormReferences(setFormReferences(byProcessDefinitionIds,
                    historicProcessInstance.getProcessDefinitionId()));
            return reference;

        }).collect(Collectors.toList());

        caseDetail.setProcessInstances(instanceReferences);
        applicationEventPublisher.publishEvent(new CaseAudit(this,
                businessKey,
                "GET", platformUser));

        stopWatch.stop();

        log.info("Total time taken to get case details for '{}' was '{}' seconds", businessKey,
                stopWatch.getTotalTimeSeconds());


        log.info("Returning case details to '{}' with business key '{}'", platformUser.getEmail(), businessKey);
        return caseDetail;
    }

    private List<CaseDetail.FormReference> setFormReferences(Map<String, List<ObjectMetadata>> byProcessDefinitionIds,
                                                             String processDefinitionId) {

        List<CaseDetail.FormReference> references = new ArrayList<>();
        List<ObjectMetadata> metadataByProcessDefinition = byProcessDefinitionIds.get(processDefinitionId);

        references.addAll(metadataByProcessDefinition.stream().map(metadata -> {
            CaseDetail.FormReference formReference = new CaseDetail.FormReference();
            formReference.setVersionId(metadata.getUserMetaDataOf("formVersionId"));
            formReference.setName(metadata.getUserMetaDataOf("name"));
            formReference.setTitle(metadata.getUserMetaDataOf("title"));
            formReference.setDataPath(metadata.getUserMetaDataOf("key"));
            formReference.setSubmissionDate(metadata.getUserMetaDataOf("submissionDate"));
            return formReference;
        }).collect(Collectors.toList()));

        return references;
    }


}
