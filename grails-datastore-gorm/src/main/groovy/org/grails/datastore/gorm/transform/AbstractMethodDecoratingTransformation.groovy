/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.datastore.gorm.transform

import groovy.transform.CompileStatic
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.expr.*
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.classgen.VariableScopeVisitor
import org.codehaus.groovy.control.ErrorCollector
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.transform.sc.StaticCompileTransformation
import org.codehaus.groovy.transform.trait.Traits
import org.grails.datastore.gorm.GormEnhancer
import org.grails.datastore.gorm.internal.RuntimeSupport
import org.grails.datastore.mapping.core.Datastore
import org.grails.datastore.mapping.core.connections.MultipleConnectionSourceCapableDatastore
import org.springframework.beans.factory.annotation.Autowired

import javax.annotation.PostConstruct
import javax.annotation.PreDestroy
import java.lang.reflect.Modifier

import static org.codehaus.groovy.ast.ClassHelper.*
import static org.grails.datastore.gorm.transform.AstMethodDispatchUtils.*
import static org.grails.datastore.mapping.reflect.AstUtils.*

/**
 * An abstract implementation for transformations that decorate a method invocation such that
 * the method invocation is wrapped in the execution of a closure that delegates to the original logic.
 * Examples of such transformations are {@link grails.gorm.transactions.Transactional} and {@link grails.gorm.multitenancy.CurrentTenant}
 *
 * @author Graeme Rocher
 * @since 6.1
 */
@CompileStatic
abstract class AbstractMethodDecoratingTransformation extends AbstractGormASTTransformation {

    private static final Set<String> METHOD_NAME_EXCLUDES = new HashSet<String>(Arrays.asList("afterPropertiesSet", "destroy"));
    private static final Set<String> ANNOTATION_NAME_EXCLUDES = new HashSet<String>(Arrays.asList(PostConstruct.class.getName(), PreDestroy.class.getName(), "grails.web.controllers.ControllerMethod"));
    public static final String FIELD_TARGET_DATASTORE = '$targetDatastore'
    public static final String METHOD_GET_TARGET_DATASTORE = "getTargetDatastore"

    @Override
    void visit(SourceUnit source, AnnotationNode annotationNode, AnnotatedNode annotatedNode) {
        if(annotatedNode instanceof MethodNode) {
            MethodNode methodNode = (MethodNode)annotatedNode
            weaveNewMethod(source, annotationNode, methodNode.getDeclaringClass(), methodNode)
        }
        else if(annotatedNode instanceof ClassNode) {
            ClassNode classNode = (ClassNode) annotatedNode
            weaveClassNode( source, annotationNode, classNode)
        }
    }

    protected void weaveDatastoreAware(SourceUnit source, AnnotationNode annotationNode, ClassNode declaringClassNode) {
        def appliedMarker = getAppliedMarker()
        if(declaringClassNode.getNodeMetaData(appliedMarker) == appliedMarker) {
            return
        }

        declaringClassNode.putNodeMetaData(appliedMarker, appliedMarker)

        Expression connectionName = annotationNode.getMember("connection")
        boolean hasDataSourceProperty = connectionName != null
        ClassExpression gormEnhancerExpr = classX(GormEnhancer)

        Expression datastoreAttribute = annotationNode.getMember("datastore")
        ClassNode defaultType = hasDataSourceProperty ? make(MultipleConnectionSourceCapableDatastore) : make(Datastore)
        boolean hasSpecificDatastore = datastoreAttribute instanceof ClassExpression
        ClassNode datastoreType = hasSpecificDatastore ? ((ClassExpression)datastoreAttribute).getType().getPlainNodeReference() : defaultType


        FieldNode datastoreField = declaringClassNode.getField(FIELD_TARGET_DATASTORE)
        if(datastoreField == null) {
            datastoreField = declaringClassNode.addField(FIELD_TARGET_DATASTORE, Modifier.PROTECTED, datastoreType, null)

            Parameter datastoresParam = param(datastoreType.makeArray(), "datastores")
            VariableExpression datastoresVar = varX(datastoresParam)

            BlockStatement setTargetDatastoreBody
            VariableExpression datastoreFieldVar = varX(datastoreField)
            Statement assignTargetDatastore = assignS(datastoreFieldVar, callD(classX(RuntimeSupport), "findDefaultDatastore", datastoresVar))
            if(hasDataSourceProperty) {
                // $targetDatastore = RuntimeSupport.findDefaultDatastore(datastores)
                // datastore = datastore.getDatastoreForConnection(connectionName)
                setTargetDatastoreBody = block(
                    assignTargetDatastore,
                    assignS(datastoreFieldVar, callX(datastoreFieldVar, "getDatastoreForConnection", connectionName ))
                )
            }
            else {
                setTargetDatastoreBody = block(
                    assignTargetDatastore
                )
            }

            weaveSetTargetDatastoreBody(source, annotationNode, declaringClassNode, datastoresVar, setTargetDatastoreBody)

            // Add method: @Autowired void setTargetDatastore(Datastore[] datastores)
            Parameter[] setTargetDatastoreParams = params(datastoresParam)
            if( declaringClassNode.getMethod("setTargetDatastore", setTargetDatastoreParams) == null) {
                MethodNode setTargetDatastoreMethod = declaringClassNode.addMethod("setTargetDatastore", Modifier.PUBLIC, VOID_TYPE, setTargetDatastoreParams, null, setTargetDatastoreBody)

                // Autowire setTargetDatastore via Spring
                addAnnotationOrGetExisting(setTargetDatastoreMethod, Autowired)
                        .setMember("required", constX(false))

                compileMethodStatically(source, setTargetDatastoreMethod)
            }

            // Add method:
            // protected Datastore getTargetDatastore(String connectionName)
            //    if($targetDatastore != null)
            //      return $targetDatastore.getDatastoreForConnection(connectionName)
            //    else
            //      return GormEnhancer.findSingleDatastore().getDatastoreForConnection(connectionName)
            Parameter connectionNameParam = param(STRING_TYPE, "connectionName")
            MethodCallExpression datastoreLookupCall
            MethodCallExpression datastoreLookupDefaultCall
            if(hasSpecificDatastore) {
                datastoreLookupDefaultCall = callX(gormEnhancerExpr, "findDatastoreByType", classX(datastoreType.getPlainNodeReference()))
            }
            else {
                datastoreLookupDefaultCall = callX(gormEnhancerExpr, "findSingleDatastore")
            }
            datastoreLookupCall = callX(datastoreLookupDefaultCall, "getDatastoreForConnection", varX(connectionNameParam))

            Parameter[] getTargetDatastoreParams = params(connectionNameParam)
            if(declaringClassNode.getMethod(METHOD_GET_TARGET_DATASTORE, getTargetDatastoreParams) == null) {
                MethodNode mn = declaringClassNode.addMethod(METHOD_GET_TARGET_DATASTORE, Modifier.PROTECTED, datastoreType, getTargetDatastoreParams, null,
                        ifElseS(notNullX(datastoreFieldVar),
                                returnS( callX( datastoreFieldVar, "getDatastoreForConnection", varX(connectionNameParam) ) ),
                                returnS(datastoreLookupCall)
                        ))
                compileMethodStatically(source, mn)
            }
            if(declaringClassNode.getMethod(METHOD_GET_TARGET_DATASTORE, ZERO_PARAMETERS) == null) {
                MethodNode mn = declaringClassNode.addMethod(METHOD_GET_TARGET_DATASTORE, Modifier.PROTECTED,  datastoreType, ZERO_PARAMETERS, null,
                    ifElseS( notNullX(datastoreFieldVar ),
                                returnS(datastoreFieldVar),
                                returnS(datastoreLookupDefaultCall))
                )

                compileMethodStatically(source, mn)
            }
        }


    }

    protected void weaveSetTargetDatastoreBody(SourceUnit source, AnnotationNode annotationNode, ClassNode declaringClassNode, VariableExpression datastoreVar, BlockStatement setTargetDatastoreBody) {
        // no-op
    }

    protected void weaveClassNode(SourceUnit source, AnnotationNode annotationNode, ClassNode classNode) {
        weaveDatastoreAware(source, annotationNode, classNode)

        List<MethodNode> methods = new ArrayList<MethodNode>(classNode.getMethods())

        for (MethodNode md in methods) {
            String methodName = md.name
            int modifiers = md.modifiers
            if (!md.isSynthetic() && Modifier.isPublic(modifiers) && !Modifier.isAbstract(modifiers) &&
                    !Modifier.isStatic(modifiers) && !hasJunitAnnotation(md)) {
                if (hasExcludedAnnotation(md)) continue

                def startsWithSpock = methodName.startsWith('$spock')
                if (methodName.contains('$') && !startsWithSpock) continue

                if (startsWithSpock && methodName.endsWith('proc')) continue

                if (md.getAnnotations().any { AnnotationNode an -> an.classNode.name == "org.spockframework.runtime.model.DataProviderMetadata" }) {
                    continue
                }

                if (METHOD_NAME_EXCLUDES.contains(methodName)) continue

                if (isSetter(md) || isGetter(md)) continue

                // don't apply to methods added by traits
                if (hasAnnotation(md, Traits.TraitBridge.class)) continue
                // ignore methods that delegate to each other
                if (hasAnnotation(md, "grails.compiler.DelegatingMethod")) continue

                weaveNewMethod(source, annotationNode, classNode, md)
            } else if (isTestSetupOrCleanup(classNode, md)) {
                weaveTestSetupMethod(source, annotationNode, classNode, md)
            }
        }
    }

    protected boolean isTestSetupOrCleanup(ClassNode classNode, MethodNode md) {
        String methodName = md.getName()
        return (("setup".equals(methodName) || "cleanup".equals(methodName)) && isSpockTest(classNode)) ||
                 hasJunitAnnotation(md)
    }

    protected abstract String getRenamedMethodPrefix()

    protected void weaveTestSetupMethod(SourceUnit sourceUnit, AnnotationNode annotationNode, ClassNode classNode, MethodNode methodNode) {
        // no-op
    }

    protected void weaveNewMethod(SourceUnit sourceUnit, AnnotationNode annotationNode, ClassNode classNode, MethodNode methodNode) {
        Object appliedMarker = getAppliedMarker()
        if ( methodNode.getNodeMetaData(appliedMarker) == appliedMarker ) {
            return
        }

        methodNode.putNodeMetaData(appliedMarker, appliedMarker)

        weaveDatastoreAware(sourceUnit, annotationNode, classNode)


        // Move the existing logic into a new method called "$tt_methodName()"
        String renamedMethodName = getRenamedMethodPrefix() + methodNode.getName()
        Parameter[] newParameters = prepareNewMethodParameters(methodNode)

        MethodCallExpression originalMethodCall = moveOriginalCodeToNewMethod(methodNode, renamedMethodName, newParameters, classNode, sourceUnit)

        // Start constructing new method body
        BlockStatement methodBody = block()


        MethodCallExpression executeMethodCallExpression = buildDelegatingMethodCall(
                sourceUnit,
                annotationNode,
                classNode,
                methodNode,
                originalMethodCall,
                methodBody
        )

        if(methodNode.getReturnType() != VOID_TYPE) {
            methodBody.addStatement(
                returnS(
                        castX(methodNode.getReturnType(), executeMethodCallExpression)
                )
            )
        } else {
            methodBody.addStatement(
                stmt(executeMethodCallExpression)
            )
        }

        methodNode.setCode(methodBody)
        processVariableScopes(sourceUnit, classNode, methodNode)
        compileMethodStatically(sourceUnit, methodNode)
    }

    protected void compileMethodStatically(SourceUnit sourceUnit, MethodNode methodNode) {
        if (compilationUnit != null) {
            addAnnotationIfNecessary(methodNode, CompileStatic)
            def staticCompileTransformation = new StaticCompileTransformation(compilationUnit: compilationUnit)
            staticCompileTransformation.visit([new AnnotationNode(COMPILE_STATIC_TYPE), methodNode] as ASTNode[], sourceUnit)
        }
    }

    protected Parameter[] prepareNewMethodParameters(MethodNode methodNode) {
        return copyParameters(methodNode.getParameters())
    }

    /**
     * Builds the delegating method call for the given class node
     *
     * @param sourceUnit The source unit
     * @param annotationNode The annotation node
     * @param classNode The class node
     * @param methodNode The original method node
     * @param originalMethodCallExpr The method call expression that invokes the original logic
     * @param newMethodBody The new method body
     *
     * @return The method call expression that will make up the body of the new method
     */
    protected abstract MethodCallExpression buildDelegatingMethodCall(SourceUnit sourceUnit, AnnotationNode annotationNode, ClassNode classNode, MethodNode methodNode, MethodCallExpression originalMethodCallExpr, BlockStatement newMethodBody)

    /**
     * Construct a method call that wraps an original call with a closure invocation
     *
     * @param targetObject The target object
     * @param executeMethodName The method that accepts a closure
     * @param closureParameters The parameters for the closure
     * @param originalMethodCall The original method call to delegate to
     * @return The MethodCallExpression
     */
    protected MethodCallExpression makeDelegatingClosureCall(VariableExpression targetObject, String executeMethodName,  Parameter[] closureParameters, MethodCallExpression originalMethodCall, VariableScope variableScope ) {
        return makeDelegatingClosureCall(targetObject, executeMethodName, new ArgumentListExpression(), closureParameters, originalMethodCall, variableScope)
    }

    /**
     * Construct a method call that wraps an original call with a closure invocation
     *
     * @param targetObject The target object
     * @param executeMethodName The method that accepts a closure
     * @param closureParameters The parameters for the closure
     * @param originalMethodCall The original method call to delegate to
     * @return The MethodCallExpression
     */
    protected MethodCallExpression makeDelegatingClosureCall(VariableExpression targetObject, String executeMethodName, ArgumentListExpression arguments, Parameter[] closureParameters, MethodCallExpression originalMethodCall, VariableScope variableScope ) {
        final ClosureExpression closureExpression = closureX(closureParameters, createDelegingMethodBody(closureParameters, originalMethodCall))
        closureExpression.setVariableScope(
            variableScope
        )
        arguments.addExpression(closureExpression)
        final MethodCallExpression executeMethodCallExpression = callX(
                targetObject,
                executeMethodName,
                arguments)

        final MethodNode executeMethodNode = targetObject.getType().getMethod(executeMethodName, paramsForArgs(arguments))
        if (executeMethodNode != null) {
            executeMethodCallExpression.setMethodTarget(executeMethodNode)
        }
        return executeMethodCallExpression
    }

    protected Statement createDelegingMethodBody(Parameter[] parameters, MethodCallExpression originalMethodCall) {
        return stmt(originalMethodCall)
    }


    protected MethodCallExpression moveOriginalCodeToNewMethod(MethodNode methodNode, String renamedMethodName, Parameter[] newParameters, ClassNode classNode, SourceUnit source) {
        Statement body = methodNode.code
        MethodNode renamedMethodNode = new MethodNode(
                renamedMethodName,
                Modifier.PROTECTED, methodNode.getReturnType().getPlainNodeReference(),
                newParameters,
                EMPTY_CLASS_ARRAY,
                body
        )


        def newVariableScope = new VariableScope()
        for (p in newParameters) {
            newVariableScope.putDeclaredVariable(p)
        }

        renamedMethodNode.setVariableScope(
                newVariableScope
        )

        // GrailsCompileStatic and GrailsTypeChecked are not explicitly addressed
        // here but they will be picked up because they are @AnnotationCollector annotations
        // which use CompileStatic and TypeChecked...
        renamedMethodNode.addAnnotations(methodNode.getAnnotations(COMPILE_STATIC_TYPE))
        renamedMethodNode.addAnnotations(methodNode.getAnnotations(TYPE_CHECKED_TYPE))

        methodNode.setCode(null)
        classNode.addMethod(renamedMethodNode)

        // Use a dummy source unit to process the variable scopes to avoid the issue where this is run twice producing an error
        VariableScopeVisitor scopeVisitor = new VariableScopeVisitor(new SourceUnit("dummy", "dummy", source.getConfiguration(), source.getClassLoader(), new ErrorCollector(source.getConfiguration())))
        if (methodNode == null) {
            scopeVisitor.visitClass(classNode)
        } else {
            scopeVisitor.prepareVisit(classNode)
            scopeVisitor.visitMethod(renamedMethodNode)
        }

        final originalMethodCall = callX(varX("this", classNode), renamedMethodName, args(renamedMethodNode.parameters))
        originalMethodCall.setImplicitThis(false)
        originalMethodCall.setMethodTarget(renamedMethodNode)

        return originalMethodCall
    }

    protected boolean hasExcludedAnnotation(MethodNode md) {
        def excludes = ANNOTATION_NAME_EXCLUDES
        return hasExcludedAnnotation(md, excludes)
    }

    protected boolean hasExcludedAnnotation(MethodNode md, Set<String> excludes) {
        boolean excludedAnnotation = false
        for (AnnotationNode annotation : md.getAnnotations()) {
            AnnotationNode gormTransform = findAnnotation( annotation.classNode, GormASTTransformationClass)
            if (gormTransform != null || excludes.contains(annotation.getClassNode().getName())) {
                excludedAnnotation = true
                break
            }
        }
        return excludedAnnotation
    }

}
