package com.linkedin.metadata.entity;

import com.datahub.util.RecordUtils;
import com.google.common.base.Preconditions;
import com.linkedin.common.AuditStamp;
import com.linkedin.common.Status;
import com.linkedin.common.urn.Urn;
import com.linkedin.data.schema.RecordDataSchema;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.entity.EnvelopedAspect;
import com.linkedin.metadata.entity.validation.RecordTemplateValidator;
import com.linkedin.metadata.models.AspectSpec;
import com.linkedin.metadata.models.EntitySpec;
import com.linkedin.metadata.models.registry.EntityRegistry;
import com.linkedin.metadata.utils.PegasusUtils;
import com.linkedin.mxe.MetadataChangeProposal;
import com.linkedin.mxe.SystemMetadata;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import lombok.extern.slf4j.Slf4j;

import java.net.URISyntaxException;
import java.util.List;

import static com.linkedin.metadata.Constants.*;


@Slf4j
public class EntityUtils {

  private EntityUtils() {
  }

  @Nonnull
  public static String toJsonAspect(@Nonnull final RecordTemplate aspectRecord) {
    return RecordUtils.toJsonString(aspectRecord);
  }

  @Nullable
  public static Urn getUrnFromString(String urnStr) {
    try {
      return Urn.createFromString(urnStr);
    } catch (URISyntaxException e) {
      return null;
    }
  }

  @Nonnull
  public static AuditStamp getAuditStamp(Urn actor) {
    AuditStamp auditStamp = new AuditStamp();
    auditStamp.setTime(System.currentTimeMillis());
    auditStamp.setActor(actor);
    return auditStamp;
  }

  public static void ingestChangeProposals(
          @Nonnull List<MetadataChangeProposal> changes,
          @Nonnull EntityService entityService,
          @Nonnull Urn actor,
          @Nonnull Boolean async
  ) {
    // TODO: Replace this with a batch ingest proposals endpoint.
    for (MetadataChangeProposal change : changes) {
      entityService.ingestProposal(change, EntityUtils.getAuditStamp(actor), async);
    }
  }

  /**
   * Get aspect from entity
   * @param entityUrn URN of the entity
   * @param aspectName aspect name string
   * @param entityService EntityService obj
   * @param defaultValue default value if null is found
   * @return a record template of the aspect
   */
  @Nullable
  public static RecordTemplate getAspectFromEntity(
          String entityUrn,
          String aspectName,
          EntityService entityService,
          RecordTemplate defaultValue
  ) {
    Urn urn = getUrnFromString(entityUrn);
    if (urn == null) {
      return defaultValue;
    }
    try {
      RecordTemplate aspect = entityService.getAspect(urn, aspectName, 0);
      if (aspect == null) {
        return defaultValue;
      }
      return aspect;
    } catch (Exception e) {
      log.error(
              "Error constructing aspect from entity. Entity: {} aspect: {}. Error: {}",
              entityUrn,
              aspectName,
              e.toString()
      );
      return null;
    }
  }

  @Nonnull
  public static RecordTemplate toAspectRecord(
      @Nonnull final Urn entityUrn,
      @Nonnull final String aspectName,
      @Nonnull final String jsonAspect,
      @Nonnull final EntityRegistry entityRegistry) {
    return toAspectRecord(PegasusUtils.urnToEntityName(entityUrn), aspectName, jsonAspect, entityRegistry);
  }

  /**
   * @param entityName
   * @param aspectName
   * @param jsonAspect
   * @param entityRegistry
   * @return a RecordTemplate which has been validated, validation errors are logged as warnings
   */
  public static RecordTemplate toAspectRecord(
      @Nonnull final String entityName,
      @Nonnull final String aspectName,
      @Nonnull final String jsonAspect,
      @Nonnull final EntityRegistry entityRegistry) {
    final EntitySpec entitySpec = entityRegistry.getEntitySpec(entityName);
    final AspectSpec aspectSpec = entitySpec.getAspectSpec(aspectName);
    //TODO: aspectSpec can be null here
    Preconditions.checkState(aspectSpec != null, String.format("Aspect %s could not be found", aspectName));
    final RecordDataSchema aspectSchema = aspectSpec.getPegasusSchema();
    RecordTemplate aspectRecord = RecordUtils.toRecordTemplate(aspectSpec.getDataTemplateClass(), jsonAspect);
    RecordTemplateValidator.validate(aspectRecord, validationFailure -> {
      log.warn(String.format("Failed to validate record %s against its schema.", aspectRecord));
    });
    return aspectRecord;
  }

  public static SystemMetadata parseSystemMetadata(String jsonSystemMetadata) {
    if (jsonSystemMetadata == null || jsonSystemMetadata.equals("")) {
      SystemMetadata response = new SystemMetadata();
      response.setRunId(DEFAULT_RUN_ID);
      response.setLastObserved(0);
      return response;
    }
    return RecordUtils.toRecordTemplate(SystemMetadata.class, jsonSystemMetadata);
  }

  /**
   * Check if entity is removed (removed=true in Status aspect) and exists 
   */
  public static boolean checkIfRemoved(EntityService entityService, Urn entityUrn) {
    try {
      
      if (!entityService.exists(entityUrn)) {
        return false;
      }
      
      EnvelopedAspect statusAspect =
          entityService.getLatestEnvelopedAspect(entityUrn.getEntityType(), entityUrn, "status");
      if (statusAspect == null) {
        return false;
      }
      Status status = new Status(statusAspect.getValue().data());
      return status.isRemoved();
    } catch (Exception e) {
      log.error("Error while checking if {} is removed", entityUrn, e);
      return false;
    }
  }
}
