package org.fcrepo.server.security.xacml.pdp.finder.attribute;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.fcrepo.server.security.xacml.MelcoeXacmlException;
import org.fcrepo.server.security.xacml.pdp.finder.AttributeFinderConfigUtil;
import org.fcrepo.server.security.xacml.pdp.finder.AttributeFinderException;
import org.fcrepo.server.security.xacml.util.AttributeFinderConfig.Designator.Attribute;
import org.fcrepo.server.security.xacml.util.ContextUtil;
import org.fcrepo.server.security.xacml.util.RelationshipResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.xacml.EvaluationCtx;
import com.sun.xacml.attr.AttributeDesignator;
import com.sun.xacml.attr.AttributeFactory;
import com.sun.xacml.attr.AttributeValue;
import com.sun.xacml.attr.BagAttribute;
import com.sun.xacml.attr.StandardAttributeFactory;
import com.sun.xacml.cond.EvaluationResult;
import com.sun.xacml.finder.AttributeFinderModule;

public class FedoraRIAttributeFinder
        extends AttributeFinderModule {

    private static final Logger logger =
            LoggerFactory.getLogger(FedoraRIAttributeFinder.class);

    private AttributeFactory attributeFactory = null;

    private RelationshipResolver relationshipResolver = null;

    //private AttributeFinderConfig attributes = null;

    private final Map<Integer,Map<String,Attribute>> m_attributes = new HashMap<Integer,Map<String,Attribute>>();

    public FedoraRIAttributeFinder() {
        try {
            Map<String, String> resolverConfig =
                    AttributeFinderConfigUtil.getResolverConfig(this.getClass()
                            .getName());
            if (logger.isDebugEnabled()) {
                for (String s : resolverConfig.keySet()) {
                    logger.debug(s + ": " + resolverConfig.get(s));
                }
            }

            relationshipResolver =
                    ContextUtil.getInstance().getRelationshipResolver();

            attributeFactory = StandardAttributeFactory.getFactory();
        } catch (AttributeFinderException afe) {
            logger.error("Attribute finder not initialised:"
                    + this.getClass().getName(), afe);
        }
    }

    public void setActionAttributes(Map<String,Attribute> attributes){
        setAttributes(AttributeDesignator.ACTION_TARGET,attributes);
    }

    public void setEnvironmentAttributes(Map<String,Attribute> attributes){
        setAttributes(AttributeDesignator.ENVIRONMENT_TARGET,attributes);
    }

    public void setResourceAttributes(Map<String,Attribute> attributes){
        setAttributes(AttributeDesignator.RESOURCE_TARGET,attributes);
    }

    public void setSubjectAttributes(Map<String,Attribute> attributes){
        setAttributes(AttributeDesignator.SUBJECT_TARGET,attributes);
    }

    protected void setAttributes(int designator, Map<String,Attribute> attributes){
        m_attributes.put(designator,attributes);
        if (logger.isDebugEnabled()) {
            logger.debug("registering the following attributes: ");
            for (String attrName : attributes.keySet()) {
                logger.debug(designator + ": " + attrName);
            }
        }

    }


    /**
     * Returns true always because this module supports designators.
     *
     * @return true always
     */
    @Override
    public boolean isDesignatorSupported() {
        return true;
    }

    /**
     * Returns a <code>Set</code> with a single <code>Integer</code> specifying
     * that environment attributes are supported by this module.
     *
     * @return a <code>Set</code> with
     *         <code>AttributeDesignator.ENVIRONMENT_TARGET</code> included
     */
    @Override
    public Set<Integer> getSupportedDesignatorTypes() {
        return m_attributes.keySet();
    }

    /**
     * Used to get an attribute. If one of those values isn't being asked for,
     * or if the types are wrong, then an empty bag is returned.
     *
     * @param attributeType
     *        the datatype of the attributes to find, which must be time, date,
     *        or dateTime for this module to resolve a value
     * @param attributeId
     *        the identifier of the attributes to find, which must be one of the
     *        three ENVIRONMENT_* fields for this module to resolve a value
     * @param issuer
     *        the issuer of the attributes, or null if unspecified
     * @param subjectCategory
     *        the category of the attribute or null, which ignored since this
     *        only handles non-subjects
     * @param context
     *        the representation of the request data
     * @param designatorType
     *        the type of designator, which must be ENVIRONMENT_TARGET for this
     *        module to resolve a value
     * @return the result of attribute retrieval, which will be a bag with a
     *         single attribute, an empty bag, or an error
     */
    @Override
    public EvaluationResult findAttribute(URI attributeType,
                                          URI attributeId,
                                          URI issuer,
                                          URI subjectCategory,
                                          EvaluationCtx context,
                                          int designatorType) {

        String resourceId = context.getResourceId().encode();
        if (logger.isDebugEnabled()) {
            logger.debug("RIAttributeFinder: [" + attributeType.toString() + "] "
                    + attributeId + ", rid=" + resourceId);
        }

        if (resourceId == null || resourceId.equals("")) {
            return new EvaluationResult(BagAttribute
                    .createEmptyBag(attributeType));
        }

        if (resourceId.equals("/FedoraRepository")) {
            return new EvaluationResult(BagAttribute
                                        .createEmptyBag(attributeType));
        }

        // figure out which attribute we're looking for
        String attrName = attributeId.toString();

        // we only know about registered attributes from config file
        Map<String,Attribute> allowedAttributes = m_attributes.get(designatorType);

        if (allowedAttributes == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Does not know about designatorType: "
                        + designatorType);
            }
            return new EvaluationResult(BagAttribute
                    .createEmptyBag(attributeType));
        }

        Attribute attribute = allowedAttributes.get(attrName);

        if (attribute == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Does not know about attribute: " + attrName);
            }
            return new EvaluationResult(BagAttribute
                    .createEmptyBag(attributeType));
        }


        EvaluationResult result = null;
        try {
            result = getEvaluationResult(resourceId, attrName, attribute, attributeType);
        } catch (Exception e) {
            logger.error("Error finding attribute: " + e.getMessage(), e);
            return new EvaluationResult(BagAttribute
                    .createEmptyBag(attributeType));
        }

        return result;
    }

    /**
     *
     * @param resourceID - the hierarchical XACML resource ID
     * @param attribute - attribute to get - this is a URI that maps to a Fedora relationship name
     * @param type
     * @return
     * @throws AttributeFinderException
     */
    private EvaluationResult getEvaluationResult(String resourceID,
                                                 String attributeID,
                                                 Attribute attribute,
                                                 URI type)
            throws AttributeFinderException {

        // split up the path of the hierarchical resource id
        String resourceParts[] = resourceID.split("/");
        Set<String> results;

        // either the last part is the pid, or the last-but one is the pid and the last is the datastream
        // if we have a pid, we query on that, if we have a datastream we query on the datastream
        String subject; // the full subject, ie pid or pid/ds
        String pid;
        if (resourceParts.length > 1) {
            if (resourceParts[resourceParts.length - 1].contains(":")) { // ends with a pid, we have pid only
                subject = resourceParts[resourceParts.length - 1];
                pid = subject;
            } else { // datastream
                pid = resourceParts[resourceParts.length - 2];
                subject = pid + "/" + resourceParts[resourceParts.length - 1];
            }
        } else {
            // eg /FedoraRepository, not a valid path to PID or PID/DS
            logger.debug("Resource ID not valid path to PID or datastream: " + resourceID);
            return new EvaluationResult(BagAttribute.createEmptyBag(type));
        }

        logger.debug("Getting attribute for resource " + subject);

        // the different types of RI attribute specification...
        // if there is no "query" option for the attribute
        String query = attribute.get("query");
        if (query == null) {
            // it's a simple relationship lookup
            // see if a relationship is specified, otherwise default to the attribute name URI
            String relationship = attribute.get("relationship");
            if (relationship == null) {
                relationship = attributeID; // default to use attribute URI as relationship if none specified
            }

            // see if we are querying based on the resource (object, datstream etc) or just on the object (pid)
            String target = attribute.get("target");
            String queryTarget;
            if (target != null && target.equals("object")) {
                queryTarget = pid;
            } else {
                queryTarget = subject;
            }

            Map<String, Set<String>> relationships;

            try {
                logger.debug("Getting attribute using relationship " + relationship);
                relationships = relationshipResolver.getRelationships(queryTarget, relationship);
            } catch (MelcoeXacmlException e) {
                throw new AttributeFinderException(e.getMessage(), e);
            }

            if (relationships == null || relationships.isEmpty()) {
                return new EvaluationResult(BagAttribute.createEmptyBag(type));
            }

            // there will only be results for one attribute, this will get all the values
            results = relationships.get(relationship);

        } else {
            // get the language and query output variable
            String queryLang = attribute.get("queryLang"); // language
            String variable =  attribute.get("value"); // query text
            String resource =  attribute.get("resource"); // resource marker in query
            String object = attribute.get("object"); // object/pid marker in query

            String subjectURI = "info:fedora/" + subject;
            String pidURI  = "info:fedora/" + pid;

            // replace the resource marker in the query with the subject
            if (resource != null) {
                query = query.replace(resource, subjectURI);
            }
            // and the pid/object marker
            if (object != null) {
                query = query.replace(object, pidURI);
            }

            // run it
            try {
                logger.debug("Using a " + queryLang + " query to get attribute " + attribute);
                results = relationshipResolver.getAttributesFromQuery(query, queryLang, variable);
            } catch (MelcoeXacmlException e) {
                throw new AttributeFinderException(e.getMessage(), e);
            }
        }

        Set<AttributeValue> bagValues = new HashSet<AttributeValue>();
        logger.debug("Attribute values found: " + results.size());
        for (String s : results) {
            AttributeValue attributeValue = null;
            try {
                attributeValue = attributeFactory.createValue(type, s);
            } catch (Exception e) {
                logger.error("Error creating attribute: " + e.getMessage(), e);
                continue;
            }

            bagValues.add(attributeValue);

            if (logger.isDebugEnabled()) {
                logger.debug("AttributeValue found: [" + type.toASCIIString()
                             + "] " + s);
            }

        }

        BagAttribute bag = new BagAttribute(type, bagValues);
        return new EvaluationResult(bag);

    }
}