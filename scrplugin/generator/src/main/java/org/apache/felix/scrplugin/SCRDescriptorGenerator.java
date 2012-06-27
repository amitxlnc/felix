/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.scrplugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.felix.scrplugin.annotations.AnnotationProcessor;
import org.apache.felix.scrplugin.description.ClassDescription;
import org.apache.felix.scrplugin.description.ComponentDescription;
import org.apache.felix.scrplugin.description.PropertyDescription;
import org.apache.felix.scrplugin.description.PropertyType;
import org.apache.felix.scrplugin.description.PropertyUnbounded;
import org.apache.felix.scrplugin.description.ReferenceCardinality;
import org.apache.felix.scrplugin.description.ReferenceDescription;
import org.apache.felix.scrplugin.description.ReferencePolicyOption;
import org.apache.felix.scrplugin.description.ReferenceStrategy;
import org.apache.felix.scrplugin.description.ServiceDescription;
import org.apache.felix.scrplugin.helper.AnnotationProcessorManager;
import org.apache.felix.scrplugin.helper.ClassModifier;
import org.apache.felix.scrplugin.helper.ClassScanner;
import org.apache.felix.scrplugin.helper.ComponentContainer;
import org.apache.felix.scrplugin.helper.DescriptionContainer;
import org.apache.felix.scrplugin.helper.IssueLog;
import org.apache.felix.scrplugin.helper.StringUtils;
import org.apache.felix.scrplugin.helper.Validator;
import org.apache.felix.scrplugin.om.metatype.AttributeDefinition;
import org.apache.felix.scrplugin.om.metatype.Designate;
import org.apache.felix.scrplugin.om.metatype.MTObject;
import org.apache.felix.scrplugin.om.metatype.MetaData;
import org.apache.felix.scrplugin.om.metatype.OCD;
import org.apache.felix.scrplugin.xml.ComponentDescriptorIO;
import org.apache.felix.scrplugin.xml.MetaTypeIO;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.metatype.MetaTypeService;

/**
 * The <code>SCRDescriptorGenerator</code> class does the hard work of
 * generating the SCR descriptors. This class is being instantiated and
 * configured by clients and the {@link #execute()} method called to generate
 * the descriptor files.
 * <p>
 * When using this class carefully consider calling <i>all</i> setter methods to properly configure the generator. All setter
 * method document, which default value is assumed for the respective property if the setter is not called.
 * <p>
 * Instances of this class are not thread save and should not be reused.
 */
public class SCRDescriptorGenerator {

    private final Log logger;

    /** The project. */
    private Project project;

    /** The options. */
    private Options options = new Options();

    /** The annotation scanner. */
    private ClassScanner scanner;

    /** The issue log. */
    private IssueLog iLog;

    /**
     * Create an instance of this generator using the given {@link Log} instance
     * of logging.
     */
    public SCRDescriptorGenerator(final Log logger) {
        this.logger = logger;
    }

    /**
     * Set the project. This is required.
     */
    public void setProject(final Project p) {
        this.project = p;
    }

    /**
     * Set the options.
     */
    public void setOptions(final Options p) {
        this.options = p;
    }

    /**
     * Actually generates the Declarative Services and Metatype descriptors
     * scanning the java sources provided by the {@link #setProject(Project)}
     *
     * @return A list of generated file names, relative to the output directory
     *
     * @throws SCRDescriptorException
     * @throws SCRDescriptorFailureException
     */
    public Result execute() throws SCRDescriptorException, SCRDescriptorFailureException {
        if (this.project == null) {
            throw new SCRDescriptorFailureException("Project has not been set!");
        }
        if (this.options == null) {
            // use default options
            this.options = new Options();
        }

        this.logger.debug("Starting SCRDescriptorMojo....");
        this.logger.debug("..generating accessors: " + this.options.isGenerateAccessors());

        // check speck version configuration
        SpecVersion specVersion = options.getSpecVersion();
        if (specVersion == null) {
            this.logger.debug("..auto detecting spec version");
        } else {
            this.logger.debug("..using spec version " + specVersion.getName());
        }

        // create a log
        this.iLog = new IssueLog(this.options.isStrictMode());

        // create the annotation processor manager
        final AnnotationProcessor aProcessor = new AnnotationProcessorManager(this.logger,
                        this.project.getClassLoader());

        // create the class scanner - and start scanning
        this.scanner = new ClassScanner(logger, iLog, project, aProcessor);
        final List<ClassDescription> scannedDescriptions = scanner.scanSources();

        // setup metadata
        final MetaData metaData = new MetaData();
        metaData.setLocalization(MetaTypeService.METATYPE_DOCUMENTS_LOCATION + "/metatype");

        final List<ComponentContainer> processedComponents = new ArrayList<ComponentContainer>();
        for (final ClassDescription desc : scannedDescriptions) {
            this.logger.debug("Processing component class " + desc.getSource());

            // check if there is more than one component definition
            if (desc.getDescriptions(ComponentDescription.class).size() > 1) {
                iLog.addError("Class has more than one component definition." +
                             " Check the annotations and merge the definitions to a single definition.",
                                desc.getSource());
            } else {
                try {
                    final ComponentContainer comp = this.createComponent(desc, metaData, iLog);
                    if (comp.getComponentDescription().getSpecVersion() != null) {
                        if ( specVersion == null ) {
                            specVersion = comp.getComponentDescription().getSpecVersion();
                            logger.debug("Setting used spec version to " + specVersion);
                        } else if (comp.getComponentDescription().getSpecVersion().ordinal() > specVersion.ordinal() && this.options.getSpecVersion() != null) {
                            // if a spec version has been configured and a component requires a higher
                            // version, this is considered an error!
                            iLog.addError("Component " + comp + " requires spec version " + comp.getComponentDescription().getSpecVersion().name()
                                            + " but plugin is configured to use version " + this.options.getSpecVersion(),
                                            desc.getSource());
                        }
                    }
                    processedComponents.add(comp);
                } catch (final SCRDescriptorException sde) {
                    iLog.addError(sde.getMessage(), sde.getSourceLocation());
                }
            }
        }
        // if spec version is still not set, we're using lowest available
        if ( specVersion == null ) {
            specVersion = SpecVersion.VERSION_1_0;
            logger.debug("Using default spec version " + specVersion);
        }
        this.logger.debug("Generating descriptor for spec version: " + specVersion);
        options.setSpecVersion(specVersion);

        // now check for abstract components and fill components objects
        final DescriptionContainer module = new DescriptionContainer(this.options);

        for (final ComponentContainer comp : processedComponents) {
            final int errorCount = iLog.getNumberOfErrors();

            final Validator validator = new Validator(comp, project, options, iLog);

            if ( this.options.isGenerateAccessors() ) {
                // before we can validate we should check the references for bind/unbind method
                // in order to create them if possible

                for (final ReferenceDescription ref : comp.getReferences().values()) {
                    // if this is a field with a single cardinality,
                    // we look for the bind/unbind methods
                    // and create them if they are not availabe
                    if (ref.getStrategy() != ReferenceStrategy.LOOKUP && ref.getField() != null
                        && (ref.getCardinality() == ReferenceCardinality.OPTIONAL_UNARY || ref.getCardinality() == ReferenceCardinality.MANDATORY_UNARY)) {

                        final String bindValue = ref.getBind();
                        final String unbindValue = ref.getUnbind();
                        final String name = ref.getName();
                        final String type = ref.getInterfaceName();

                        boolean createBind = false;
                        boolean createUnbind = false;

                        // Only create method if no bind name has been specified
                        if (bindValue == null && validator.findMethod(ref, "bind") == null) {
                            // create bind method
                            createBind = true;
                        }
                        if (unbindValue == null && validator.findMethod(ref, "unbind") == null) {
                            // create unbind method
                            createUnbind = true;
                        }
                        if (createBind || createUnbind) {
                            ClassModifier.addMethods(comp.getClassDescription().getDescribedClass().getName(),
                                            name,
                                            ref.getField().getName(),
                                            type,
                                            createBind,
                                            createUnbind,
                                            this.project.getClassesDirectory());
                        }
                    }
                }
            }
            validator.validate();

            // ignore component if it has errors
            if (iLog.getNumberOfErrors() == errorCount) {
                if (!comp.getComponentDescription().isCreateDs()) {
                    logger.debug("Ignoring descriptor for DS : " + comp);
                } else if (!comp.getComponentDescription().isAbstract()) {
                    this.logger.debug("Adding descriptor for DS : " + comp);
                    module.addComponent(comp);
                }
            }
        }

        // log issues
        iLog.logMessages(logger);

        // after checking all classes, throw if there were any failures
        if (iLog.hasErrors()) {
            throw new SCRDescriptorFailureException("SCR Descriptor parsing had failures (see log)");
        }

        final Result result = new Result();
        // write meta type info if there is a file name
        if (!StringUtils.isEmpty(this.options.getMetaTypeName())) {
            final String path = "OSGI-INF" + File.separator + "metatype" + File.separator + this.options.getMetaTypeName();
            final File mtFile = new File(this.options.getOutputDirectory(), path);
            final int size = metaData.getOCDs().size() + metaData.getDesignates().size();
            if (size > 0) {
                this.logger.info("Generating " + size + " MetaType Descriptors to " + mtFile);
                mtFile.getParentFile().mkdirs();
                MetaTypeIO.write(metaData, mtFile);
                result.setMetatypeFiles(Collections.singletonList(path.replace(File.separatorChar, '/')));
            } else {
                if (mtFile.exists()) {
                    mtFile.delete();
                }
            }

        } else {
            this.logger.info("Meta type file name is not set: meta type info is not written.");
        }

        // check descriptor file
        final String descriptorPath = "OSGI-INF" + File.separator + this.options.getSCRName();
        final File descriptorFile = StringUtils.isEmpty(this.options.getSCRName()) ? null : new File(this.options.getOutputDirectory(), descriptorPath);

        // terminate if there is nothing else to write
        if (module.getComponents().isEmpty()) {
            this.logger.debug("No Service Component Descriptors found in project.");
            // remove file if it exists
            if (descriptorFile != null && descriptorFile.exists()) {
                this.logger.debug("Removing obsolete service descriptor " + descriptorFile);
                descriptorFile.delete();
            }
        } else {
            if (descriptorFile == null) {
                throw new SCRDescriptorFailureException("Descriptor file name must not be empty.");
            }

            // finally the descriptors have to be written ....
            descriptorFile.getParentFile().mkdirs(); // ensure parent dir

            this.logger.info("Writing " + module.getComponents().size() + " Service Component Descriptors to "
                            + descriptorFile);

            ComponentDescriptorIO.write(module, descriptorFile);
            result.setScrFiles(Collections.singletonList(descriptorPath.replace(File.separatorChar, '/')));
        }

        return result;
    }

    /**
     * Create the SCR objects based on the descriptions
     */
    private ComponentContainer createComponent(final ClassDescription desc,
                    final MetaData metaData,
                    final IssueLog iLog)
    throws SCRDescriptorException, SCRDescriptorFailureException {
        final ComponentDescription componentDesc = desc.getDescription(ComponentDescription.class);

        // configuration pid in 1.2
        if ( componentDesc.getConfigurationPid() != null && !componentDesc.getConfigurationPid().equals(componentDesc.getName())) {
            componentDesc.setSpecVersion(SpecVersion.VERSION_1_2);
        }

        // Create metatype (if required)
        final OCD ocd;
        if ( !componentDesc.isAbstract() && componentDesc.isCreateMetatype() ) {
            // OCD
            ocd = new OCD();
            metaData.addOCD( ocd );
            ocd.setId( componentDesc.getName() );
            if ( componentDesc.getLabel() != null ) {
                ocd.setName( componentDesc.getLabel() );
            } else {
                ocd.setName( "%" + componentDesc.getName() + ".name");
            }
            if ( componentDesc.getDescription() != null ) {
                ocd.setDescription( componentDesc.getDescription() );
            } else {
                ocd.setDescription( "%" + componentDesc.getName() + ".description");
            }

            // Designate
            final Designate designate = new Designate();
            metaData.addDesignate( designate );
            designate.setPid( componentDesc.getName() );

            // Factory pid
            if ( componentDesc.isSetMetatypeFactoryPid() ) {
                if ( componentDesc.getFactory() == null ) {
                    designate.setFactoryPid( componentDesc.getName() );
                } else {
                    iLog.addWarning( "Component factory " + componentDesc.getName()
                        + " should not set metatype factory pid.", desc.getSource() );
                }
            }
            // MTObject
            final MTObject mtobject = new MTObject();
            designate.setObject( mtobject );
            mtobject.setOcdref( componentDesc.getName() );
        } else {
            ocd = null;
        }

        final ComponentContainer container = new ComponentContainer(desc, componentDesc);

        ClassDescription current = desc;
        boolean inherit;
        do {
            final ComponentDescription cd = current.getDescription(ComponentDescription.class);
            inherit = (cd == null ? true : cd.isInherit());

            if ( cd != null ) {
                // handle enabled and immediate
                if ( componentDesc.getEnabled() == null ) {
                    componentDesc.setEnabled(cd.getEnabled());
                }
                if ( componentDesc.getImmediate() == null ) {
                    componentDesc.setImmediate(cd.getImmediate());
                }

                // lifecycle methods
                if ( componentDesc.getActivate() == null && cd.getActivate() != null ) {
                    componentDesc.setActivate(cd.getActivate());
                }
                if ( componentDesc.getDeactivate() == null && cd.getDeactivate() != null ) {
                    componentDesc.setDeactivate(cd.getDeactivate());
                }
                if ( componentDesc.getModified() == null && cd.getModified() != null ) {
                    componentDesc.setModified(cd.getModified());
                }
                if ( componentDesc.getActivate() != null || componentDesc.getDeactivate() != null || componentDesc.getModified() != null ) {
                    // spec version must be at least 1.1
                    componentDesc.setSpecVersion(SpecVersion.VERSION_1_1);
                }
            }

            // services, properties, references
            this.processServices(current, container);
            this.processProperties(current, container, ocd);
            this.processReferences(current, container);

            // go up in the class hierarchy
            if ( !inherit || current.getDescribedClass().getSuperclass() == null ) {
                current = null;
            } else {
                current = this.scanner.getDescription(current.getDescribedClass().getSuperclass());
            }
        } while ( inherit && current != null);

        // PID handling
        if ( componentDesc.isCreatePid() && !container.getProperties().containsKey(org.osgi.framework.Constants.SERVICE_PID)) {
            final PropertyDescription pid = new PropertyDescription(null);
            pid.setName( org.osgi.framework.Constants.SERVICE_PID );
            pid.setValue( componentDesc.getName() );

            container.getProperties().put(org.osgi.framework.Constants.SERVICE_PID, pid);
        }
        this.processGlobalProperties(desc, container.getProperties());

        return container;
    }

    /**
     * Process service directives
     */
    private void processServices(final ClassDescription current, final ComponentContainer component)
    throws SCRDescriptorException, SCRDescriptorFailureException {

        final ServiceDescription serviceDesc = current.getDescription(ServiceDescription.class);
        if ( serviceDesc != null ) {
            ServiceDescription service = component.getServiceDescription();
            if ( service == null ) {
                service = new ServiceDescription(serviceDesc.getAnnotation());
                service.setServiceFactory(false);
                component.setServiceDescription(service);
            }
            if ( serviceDesc.isServiceFactory() ) {
                service.setServiceFactory(true);
            }
            for(final String className : serviceDesc.getInterfaces()) {
                service.addInterface(className);
            }
        }
    }

    /**
     * Process property directives
     */
    private void processProperties(
                    final ClassDescription current,
                    final ComponentContainer component,
                    final OCD ocd)
    throws SCRDescriptorException, SCRDescriptorFailureException {
        for(final PropertyDescription pd : current.getDescriptions(PropertyDescription.class)) {

            // metatype - is this property private?
            final boolean isPrivate;
            if ( pd.isPrivate() != null ) {
                isPrivate = pd.isPrivate();
            } else {
                final String name = pd.getName();
                if (org.osgi.framework.Constants.SERVICE_RANKING.equals(name)
                    || org.osgi.framework.Constants.SERVICE_PID.equals(name)
                    || org.osgi.framework.Constants.SERVICE_DESCRIPTION.equals(name)
                    || org.osgi.framework.Constants.SERVICE_ID.equals(name)
                    || org.osgi.framework.Constants.SERVICE_VENDOR.equals(name)
                    || ConfigurationAdmin.SERVICE_BUNDLELOCATION.equals(name)
                    || ConfigurationAdmin.SERVICE_FACTORYPID.equals(name) ) {
                    isPrivate = true;
                } else {
                    isPrivate = false;
                }
            }
            if ( !isPrivate && ocd != null ) {
                final AttributeDefinition ad = new AttributeDefinition();
                ocd.getProperties().add(ad);
                ad.setId(pd.getName());
                ad.setType(pd.getType().name());

                if (pd.getLabel() != null ) {
                    ad.setName(pd.getLabel());
                } else {
                    ad.setName("%" + pd.getName() + ".name");
                }
                if (pd.getDescription() != null ) {
                    ad.setDescription(pd.getDescription());
                } else {
                    ad.setDescription("%" + pd.getName() + ".description");
                }

                if ( pd.getUnbounded() == PropertyUnbounded.DEFAULT ) {
                    ad.setCardinality(pd.getCardinality());
                } else if ( pd.getUnbounded() == PropertyUnbounded.ARRAY ) {
                    // unlimited array
                    ad.setCardinality(new Integer(Integer.MAX_VALUE));
                } else {
                    // unlimited vector
                    ad.setCardinality(new Integer(Integer.MIN_VALUE));
                }

                ad.setDefaultValue(pd.getValue());
                ad.setDefaultMultiValue(pd.getMultiValue());

                // check options
                final String[] parameters = pd.getOptions();
                if ( parameters != null && parameters.length > 0 ) {
                    final Map<String, String> options = new LinkedHashMap<String, String>();
                    for (int j=0; j < parameters.length; j=j+2) {
                        final String optionLabel = parameters[j];
                        final String optionValue = (j < parameters.length-1) ? parameters[j+1] : null;
                        if (optionValue != null) {
                            options.put(optionLabel, optionValue);
                        }
                    }
                    ad.setOptions(options);
                }

            }
            this.testProperty(current, component.getProperties(), pd, current == component.getClassDescription());
        }
    }

    /**
     * Add global properties (if not already defined in the component)
     */
    private void processGlobalProperties(final ClassDescription desc,
                    final Map<String, PropertyDescription> allProperties) {
        // apply pre configured global properties
        if ( this.options.getProperties() != null ) {
            for(final Map.Entry<String, String> entry : this.options.getProperties().entrySet()) {
                final String propName = entry.getKey();
                final String value = entry.getValue();
                // check if the service already provides this property
                if ( value != null && !allProperties.containsKey(propName) ) {

                    final PropertyDescription p = new PropertyDescription(null);
                    p.setName(propName);
                    p.setValue(value);
                    p.setType(PropertyType.String);

                    allProperties.put(propName, p);
                }
            }
        }
    }

    /**
     * Test a newly found property
     */
    private void testProperty(final ClassDescription current,
                    final Map<String, PropertyDescription> allProperties,
                    final PropertyDescription newProperty,
                    boolean isInspectedClass ) {
        final String propName = newProperty.getName();

        if ( !StringUtils.isEmpty(propName) ) {
            if ( allProperties.containsKey(propName) ) {
                // if the current class is the class we are currently inspecting, we
                // have found a duplicate definition
                if ( isInspectedClass ) {
                    iLog.addError("Duplicate definition for property " + propName + " in class "
                                    + current.getDescribedClass().getName(), current.getSource() );
                }
            } else {
                allProperties.put(propName, newProperty);
            }
        }
    }

    /**
     * Process reference directives
     */
    private void processReferences(final ClassDescription current,
                    final ComponentContainer component)
    throws SCRDescriptorException, SCRDescriptorFailureException {
        for(final ReferenceDescription rd : current.getDescriptions(ReferenceDescription.class)) {
            if ( rd.getPolicyOption() != ReferencePolicyOption.RELUCTANT ) {
                component.getComponentDescription().setSpecVersion(SpecVersion.VERSION_1_2);
            }
            if ( rd.getUpdated() != null ) {
                // updated requires 1.2 or 1.1_FELIX, if nothing is set, we use 1.2
                if ( component.getComponentDescription().getSpecVersion() == null
                     || component.getComponentDescription().getSpecVersion().ordinal() < SpecVersion.VERSION_1_1_FELIX.ordinal() ) {
                    component.getComponentDescription().setSpecVersion(SpecVersion.VERSION_1_2);
                }
            }

            this.testReference(current, component.getReferences(), rd, component.getClassDescription() == current);
        }
    }

    /**
     * Test a newly found reference
     */
    private void testReference(final ClassDescription current,
                    final Map<String, ReferenceDescription> allReferences,
                    final ReferenceDescription newReference,
                    boolean isInspectedClass ) {
        String refName = newReference.getName();
        if ( refName == null) {
            refName = newReference.getInterfaceName();
        }

        if ( refName != null ) {
            if ( allReferences.containsKey( refName ) ) {
                // if the current class is the class we are currently inspecting, we
                // have found a duplicate definition
                if ( isInspectedClass ) {
                    iLog.addError("Duplicate definition for reference " + refName + " in class "
                        + current.getDescribedClass().getName(), current.getSource() );
                }
            } else {
                allReferences.put(refName, newReference);
            }
        }
    }
}
