package org.snomed.snowstorm.fhir.services;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.ValueSet.*;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.snowstorm.core.data.domain.*;
import org.snomed.snowstorm.core.data.services.*;
import org.snomed.snowstorm.core.data.services.pojo.MemberSearchRequest;
import org.snomed.snowstorm.core.data.services.pojo.PageWithBucketAggregations;
import org.snomed.snowstorm.core.pojo.LanguageDialect;
import org.snomed.snowstorm.fhir.config.FHIRConstants;
import org.snomed.snowstorm.fhir.domain.BranchPath;
import org.snomed.snowstorm.fhir.domain.ValueSetFilter;
import org.snomed.snowstorm.fhir.domain.ValueSetWrapper;
import org.snomed.snowstorm.fhir.repositories.FHIRValuesetRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.rest.annotation.*;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.param.QuantityParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import io.kaicode.rest.util.branchpathrewrite.BranchPathUriUtil;

import static org.snomed.snowstorm.config.Config.DEFAULT_LANGUAGE_DIALECTS;
import static org.snomed.snowstorm.core.data.services.ReferenceSetMemberService.AGGREGATION_MEMBER_COUNTS_BY_REFERENCE_SET;

@Component
public class FHIRValueSetProvider implements IResourceProvider, FHIRConstants {
	
	@Autowired
	private FHIRValuesetRepository valuesetRepository;
	
	@Autowired
	private QueryService queryService;
	
	@Autowired
	private ConceptService conceptService;
	
	@Autowired
	private ReferenceSetMemberService refsetService;
	
	@Autowired
	private HapiValueSetMapper mapper;
	
	@Autowired
	private FHIRHelper fhirHelper;
	
	private static int DEFAULT_PAGESIZE = 1000;
	
	private final Logger logger = LoggerFactory.getLogger(getClass());
	
	@Read()
	public ValueSet getValueSet(@IdParam IdType id) {
		Optional<ValueSetWrapper> vsOpt = valuesetRepository.findById(id.getIdPart());
		if (vsOpt.isPresent()) {
			ValueSet vs = vsOpt.get().getValueset();
			//If we're not calling the expansion operation, don't include that element
			vs.setExpansion(null);
			return vs;
		}
		return null;
	}
	
	@Create()
	public MethodOutcome createValueset(@IdParam IdType id, @ResourceParam ValueSet vs) throws FHIROperationException {
		MethodOutcome outcome = new MethodOutcome();
		validateId(id, vs);
		
		//Attempt to expand the valueset in lieu of full validation
		if (vs != null && vs.getCompose() != null && !vs.getCompose().isEmpty()) {
			obtainConsistentCodeSystemVersionFromCompose(vs.getCompose());
			covertComposeToEcl(vs.getCompose());
		}
		
		ValueSetWrapper savedVs = valuesetRepository.save(new ValueSetWrapper(id, vs));
		int version = 1;
		if (id.hasVersionIdPart()) {
			version += id.getVersionIdPartAsLong().intValue();
		}
		outcome.setId(new IdType("ValueSet", savedVs.getId(), Long.toString(version)));
		return outcome;
	}

	@Update
	public MethodOutcome updateValueset(@IdParam IdType id, @ResourceParam ValueSet vs) throws FHIROperationException {
		try {
			return createValueset(id, vs);
		} catch (Exception e) {
			throw new FHIROperationException(IssueType.EXCEPTION, "Failed to update/create valueset '" + vs.getId(),e);
		}
	}
	
	@Delete
	public void deleteValueset(@IdParam IdType id) {
		valuesetRepository.deleteById(id.getIdPart());
	}
	
	
	//See https://www.hl7.org/fhir/valueset.html#search
	@Search
	public List<ValueSet> findValuesets(
			HttpServletRequest theRequest, 
			HttpServletResponse theResponse,
			@OptionalParam(name="code") String code,
			@OptionalParam(name="context") String context,
			@OptionalParam(name="context-quantity") QuantityParam contextQuantity,
			@OptionalParam(name="context-type") String contextType,
			@OptionalParam(name="date") String date,
			@OptionalParam(name="description") String description,
			@OptionalParam(name="expansion") String expansion,
			@OptionalParam(name="identifier") String identifier,
			@OptionalParam(name="jurisdiction") String jurisdiction,
			@OptionalParam(name="name") String name,
			@OptionalParam(name="publisher") String publisher,
			@OptionalParam(name="reference") String reference,
			@OptionalParam(name="status") String status,
			@OptionalParam(name="title") String title,
			@OptionalParam(name="url") String url,
			@OptionalParam(name="version") String version) {
		ValueSetFilter vsFilter = new ValueSetFilter()
									.withCode(code)
									.withContext(context)
									.withContextQuantity(contextQuantity)
									.withContextType(contextType)
									.withDate(date)
									.withDescription(description)
									.withExpansion(expansion)
									.withIdentifier(identifier)
									.withJurisdiction(jurisdiction)
									.withName(name)
									.withPublisher(publisher)
									.withReference(reference)
									.withStatus(status)
									.withTitle(title)
									.withUrl(url)
									.withVersion(version);
		//logger.info("Filtering {} valueSets", valuesetRepository.count());
		
		return StreamSupport.stream(valuesetRepository.findAll().spliterator(), false)
				.map(vs -> vs.getValueset())
				.filter(vs -> ValueSetFilter.apply(vsFilter, vs, fhirHelper))
				.collect(Collectors.toList());
	}
	
	@Operation(name="$expand", idempotent=true)
	public ValueSet expandInstance(@IdParam IdType id,
			HttpServletRequest request,
			HttpServletResponse response,
			@OperationParam(name="url") String url,
			@OperationParam(name="filter") String filter,
			@OperationParam(name="activeOnly") BooleanType activeType,
			@OperationParam(name="includeDesignations") BooleanType includeDesignationsType,
			@OperationParam(name="designation") List<String> designations,
			@OperationParam(name="displayLanguage") String displayLanguage,
			@OperationParam(name="offset") String offsetStr,
			@OperationParam(name="count") String countStr) throws FHIROperationException {
		return expand (id, request, response, url, filter, activeType, includeDesignationsType,
				designations, displayLanguage, offsetStr, countStr);
	}
	
	@Operation(name="$expand", idempotent=true)
	public ValueSet expandType(
			HttpServletRequest request,
			HttpServletResponse response,
			@OperationParam(name="url") String url,
			@OperationParam(name="filter") String filter,
			@OperationParam(name="activeOnly") BooleanType activeType,
			@OperationParam(name="includeDesignations") BooleanType includeDesignationsType,
			@OperationParam(name="designation") List<String> designations,
			@OperationParam(name="displayLanguage") String displayLanguage,
			@OperationParam(name="offset") String offsetStr,
			@OperationParam(name="count") String countStr) throws FHIROperationException {
		return expand(null, request, response, url, filter, activeType, includeDesignationsType,
				designations, displayLanguage, offsetStr, countStr);
	}
	
	private ValueSet expand(@IdParam IdType id,
			HttpServletRequest request,
			HttpServletResponse response,
			String url,
			String filter,
			BooleanType activeType,
			BooleanType includeDesignationsType,
			List<String> designationsStr,
			String displayLanguageStr,
			String offsetStr,
			String countStr) throws FHIROperationException {
		// Are we expanding a specific named Valueset?
		ValueSet vs = null;
		if (id != null) {
			logger.info("Expanding '{}'",id.getIdPart());
			vs = getValueSet(id);
			if (vs == null) {
				return null; // Will be translated into a 404
			}
			// Are we expanding based on the URL of the named ValueSet?  Can't do both!
			if (url != null && vs.getUrl() != null) {
				throw new FHIROperationException(IssueType.VALUE, "Cannot expand both '" + vs.getUrl() + "' in " + id.getIdPart() + "' and '" + url + "' in request.");
			}
			url = vs.getUrl();
		}
		
		List<LanguageDialect> designations = fhirHelper.getLanguageDialects(designationsStr, request);
		int offset = (offsetStr == null || offsetStr.isEmpty()) ? 0 : Integer.parseInt(offsetStr);
		int pageSize = (countStr == null || countStr.isEmpty()) ? DEFAULT_PAGESIZE : Integer.parseInt(countStr);
		Boolean active = activeType == null ? null : activeType.booleanValue();
		
		// Also if displayLanguage has been used, ensure that's part of our requested Language Codes
		if (displayLanguageStr != null) {
			//If we don't already have the display language specified, add it
			if (!contains(designations, displayLanguageStr)) {
				designations.add(new LanguageDialect(displayLanguageStr));
			}
		} 

		//If someone specified designations, then include them unless specified not to, in which 
		//case use only for the displayLanguage because that's the only way to get a langRefsetId specified
		boolean includeDesignations = true;
		if (includeDesignationsType != null) {
			includeDesignations = includeDesignationsType.booleanValue();
			//If we're including designations but not specified which ones, use the default
			if (includeDesignations && designations.isEmpty()) {
				designations.addAll(DEFAULT_LANGUAGE_DIALECTS);
			}
		} else if (designationsStr == null) {
			includeDesignations = false;
		}
		
		BranchPath branchPath = new BranchPath();
		Page<ConceptMini> conceptMiniPage;

		//The code system is the URL up to where the parameters start eg http://snomed.info/sct?fhir_vs=ecl/ or http://snomed.info/sct/45991000052106?fhir_vs=ecl/
		//These calls will also set the branchPath
		int cutPoint = url == null ? -1 : url.indexOf("?");
		if (cutPoint == NOT_SET) {
			conceptMiniPage = doExplicitExpansion(vs, active, filter, branchPath, designations, offset, pageSize);
		} else {
			conceptMiniPage = doImplcitExpansion(cutPoint, url, active, filter, branchPath, designations, offset, pageSize);
		}
		
		//We will always need the PT, so recover further details
		Map<String, Concept> conceptDetails = getConceptDetailsMap(branchPath, conceptMiniPage, designations);
		ValueSet valueSet = mapper.mapToFHIR(vs, conceptMiniPage.getContent(), url, conceptDetails, designations, includeDesignations); 
		valueSet.getExpansion().setTotal((int)conceptMiniPage.getTotalElements());
		valueSet.getExpansion().setOffset(offset);
		return valueSet;
	}

	/**
	 * An implicit ValueSet is one that hasn't been saved on the server, but is being 
	 * defined at expansion time by use of a URL containing a definition of the content
	 */
	private Page<ConceptMini> doImplcitExpansion(int cutPoint, String url, Boolean active, String filter,
			BranchPath branchPath, List<LanguageDialect> designations, int offset, int pageSize) throws FHIROperationException {
		StringType codeSystemVersionUri = new StringType(url.substring(0, cutPoint));
		branchPath.set(fhirHelper.getBranchPathForCodeSystemVersion(codeSystemVersionUri));
		//Are we looking for all known refsets?  Special case.
		if (url.endsWith("?fhir_vs=refset")) {
			return findAllRefsets(branchPath, PageRequest.of(offset, pageSize));
		} else {
			String ecl = determineEcl(url);
			Page<ConceptMini> conceptMiniPage = eclSearch(ecl, active, filter, designations, branchPath, offset, pageSize);
			logger.info("Recovered: {} concepts from branch: {} with ecl: '{}'", conceptMiniPage.getContent().size(), branchPath, ecl);
			return conceptMiniPage;
		}
	}

	/**
	 * An explicit ValueSet has been saved on the server with a name and id, and 
	 * is defined by use of the "compose" element within the valueset resource.
	 */
	private Page<ConceptMini> doExplicitExpansion(ValueSet vs, Boolean active, String filter,
			BranchPath branchPath, List<LanguageDialect> designations, int offset, int pageSize) throws FHIROperationException {
		Page<ConceptMini> conceptMiniPage = new PageImpl<>(new ArrayList<>());
		if (vs != null && vs.getCompose() != null && !vs.getCompose().isEmpty()) {
			branchPath.set(obtainConsistentCodeSystemVersionFromCompose(vs.getCompose()));
			String ecl = covertComposeToEcl(vs.getCompose());
			conceptMiniPage = eclSearch(ecl, active, filter, designations, branchPath, offset, pageSize);
			logger.info("Recovered: {} concepts from branch: {} with ecl from compose: '{}'", conceptMiniPage.getContent().size(), branchPath, ecl);
		} else {
			String msg = "Compose element(s) or 'url' parameter is expected to be present for an expansion, containing eg http://snomed.info/sct?fhir_vs=ecl/ or http://snomed.info/sct/45991000052106?fhir_vs=ecl/ ";
			//We don't need ECL if we're expanding a named valueset
			if (vs != null) {
				logger.warn(msg + " when expanding " + vs.getId());
			} else {
				throw new FHIROperationException(IssueType.VALUE, msg);
			}
		}
		return conceptMiniPage;
	}

	private boolean contains(List<LanguageDialect> languageDialects, String displayLanguage) {
		return languageDialects.stream()
		.anyMatch(ld -> ld.getLanguageCode().equals(displayLanguage));
	}

	public Page<ConceptMini> eclSearch(String ecl, Boolean active, String termFilter, List<LanguageDialect> languageDialects, BranchPath branchPath, int offset, int pageSize) {
		Page<ConceptMini> conceptMiniPage;
		QueryService.ConceptQueryBuilder queryBuilder = queryService.createQueryBuilder(false);  //Inferred view only for now
		queryBuilder.ecl(ecl)
				.descriptionCriteria(descriptionCriteria -> descriptionCriteria.term(termFilter))
				.resultLanguageDialects(languageDialects)
				.activeFilter(active);
		conceptMiniPage = queryService.search(queryBuilder, BranchPathUriUtil.decodePath(branchPath.toString()), PageRequest.of(offset, pageSize));
		return conceptMiniPage;
	}

	private BranchPath obtainConsistentCodeSystemVersionFromCompose(ValueSetComposeComponent compose) throws FHIROperationException {
		String system = null;
		String version = null;
		
		//Check all include and exclude elements to ensure they have a consistent snomed URI
		List<ConceptSetComponent> allIncludeExcludes = new ArrayList<>(compose.getInclude());
		allIncludeExcludes.addAll(compose.getExclude());
		for (ConceptSetComponent thisIncludeExclude : allIncludeExcludes) {
			if (thisIncludeExclude.getSystem() != null && !thisIncludeExclude.getSystem().contains(SNOMED_URI)) {
				throw new FHIROperationException (IssueType.NOTSUPPORTED , "Server currently limited to compose elements using SNOMED CT code system");
			}
			if (thisIncludeExclude.getSystem() != null && system == null) {
				system = thisIncludeExclude.getSystem();
			}
			if (thisIncludeExclude.getVersion() != null && version == null) {
				version = thisIncludeExclude.getVersion();
			}
			if (system != null && thisIncludeExclude.getSystem() != null && !system.equals(thisIncludeExclude.getSystem())) {
				String msg = "Server currently requires consistency in ValueSet compose element code systems.";
				msg += " Encoundered both '" + system + "' and '" + thisIncludeExclude.getSystem() + "'."; 
				throw new FHIROperationException (IssueType.NOTSUPPORTED , msg);
			}
			if (version != null && thisIncludeExclude.getVersion() != null && !version.equals(thisIncludeExclude.getVersion())) {
				throw new FHIROperationException (IssueType.NOTSUPPORTED , "Server currently requires consistency in ValueSet compose element code system versions");
			}
		}
		StringType codeSystemVersionUri = new StringType(system + "/" + version);
		return fhirHelper.getBranchPathForCodeSystemVersion(codeSystemVersionUri);
	}
	
	
	public String covertComposeToEcl(ValueSetComposeComponent compose) throws FHIROperationException {
		//Successive include elements will be added using 'OR'
		//While the excludes will be added using 'MINUS'
		String ecl = "";
		boolean isFirstInclude = true;
		for (ConceptSetComponent include : compose.getInclude()) {
			if (isFirstInclude) {
				isFirstInclude = false;
			} else {
				ecl += " OR ";
			}
			ecl += "( " + fhirHelper.convertToECL(include) + " )";
		}
		
		//We need something to minus!
		if (isFirstInclude) {
			throw new FHIROperationException (IssueType.VALUE , "Invalid use of exclude without include in ValueSet compose element.");
		}
		
		for (ConceptSetComponent exclude : compose.getExclude()) {
			ecl += " MINUS ( " + fhirHelper.convertToECL(exclude) + " )";
		}
		
		return ecl;
	}

	private void validateId(IdType id, ValueSet vs) throws FHIROperationException {
		if (vs == null || id == null) {
			throw new FHIROperationException(IssueType.EXCEPTION, "Both ID and ValueSet object must be supplied");
		}
		if (vs.getId() == null || !id.asStringValue().equals(vs.getId())) {
			throw new FHIROperationException(IssueType.EXCEPTION, "ID in request must match that in ValueSet object");
		}
	}
	
	private Page<ConceptMini> findAllRefsets(BranchPath branchPath, PageRequest pageRequest) {
		PageWithBucketAggregations<ReferenceSetMember> bucketPage = refsetService.findReferenceSetMembersWithAggregations(branchPath.toString(), pageRequest, new MemberSearchRequest().active(true));
		List<ConceptMini> refsets = new ArrayList<>();
		if (bucketPage.getBuckets() != null && bucketPage.getBuckets().containsKey(AGGREGATION_MEMBER_COUNTS_BY_REFERENCE_SET)) {
			refsets = bucketPage.getBuckets().get(AGGREGATION_MEMBER_COUNTS_BY_REFERENCE_SET).keySet().stream()
					.map(s -> new ConceptMini(s, null))
					.collect(Collectors.toList());
		}
		return new PageImpl<>(refsets, pageRequest, refsets.size());
	}

	private Map<String, Concept> getConceptDetailsMap(BranchPath branchPath, Page<ConceptMini> page, List<LanguageDialect> languageDialects) {
		if (!page.hasContent()) {
			return null;
		}
		List<String> ids = page.getContent().stream()
				.map(ConceptMini::getConceptId)
				.collect(Collectors.toList());
		return conceptService.find(branchPath.toString(), ids, languageDialects).stream()
			.collect(Collectors.toMap(Concept::getConceptId, c -> c));
	}

	/**
	 * See https://www.hl7.org/fhir/snomedct.html#implicit 
	 * @param url
	 * @return
	 * @throws FHIROperationException 
	 */
	private String determineEcl(String url) throws FHIROperationException {
		String ecl;
		if (url.endsWith("?fhir_vs")) {
			// Return all of SNOMED CT in this situation
			ecl = "*";
		} else if (url.contains(IMPLICIT_ISA)) {
			String sctId = url.substring(url.indexOf(IMPLICIT_ISA) + IMPLICIT_ISA.length());
			ecl = "<<" + sctId;
		} else if (url.contains(IMPLICIT_REFSET)) {
			String sctId = url.substring(url.indexOf(IMPLICIT_REFSET) + IMPLICIT_REFSET.length());
			ecl = "^" + sctId;
		} else if (url.contains(IMPLICIT_ECL)) {
			ecl = url.substring(url.indexOf(IMPLICIT_ECL) + IMPLICIT_ECL.length());
		} else {
			throw new FHIROperationException(IssueType.VALUE, "url is expected to include parameter with value: 'fhir_vs=ecl/'");
		}
		return ecl;
	}

	@Override
	public Class<? extends IBaseResource> getResourceType() {
		return ValueSet.class;
	}
}
