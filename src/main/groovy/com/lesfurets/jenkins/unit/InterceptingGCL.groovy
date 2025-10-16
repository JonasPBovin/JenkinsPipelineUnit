package com.lesfurets.jenkins.unit

import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilerConfiguration

import static com.lesfurets.jenkins.unit.MethodSignature.method

class InterceptingGCL extends GroovyClassLoader {

    private static final Set<Class> JP_INTERCEPTED = Collections.synchronizedSet(new HashSet<Class>())

    /**
     * Intercept class methods to route via PipelineTestHelper
     * @param metaClazz meta class to intercept
     * @param helper pipeline test helper
     * @param binding binding to use for env access
     */
    static void interceptClassMethods(MetaClass metaClazz, PipelineTestHelper helper, Binding binding) {
        Class clazz = metaClazz.theClass
        if (!shouldInstrument(clazz)) {
            return
        }
        def mc = GroovySystem.metaClassRegistry.getMetaClass(clazz)
        if (!(mc instanceof ExpandoMetaClass)) {
            mc = new ExpandoMetaClass(clazz, true, true)
            mc.initialize()
            GroovySystem.metaClassRegistry.setMetaClass(clazz, mc)
        }

        // Determine if we need to (re)install: either not seen before OR invoke/missing not our closures
        boolean missingInvoke = !(mc.invokeMethod?.is(helper.getMethodInterceptor()))
        boolean missingMissing = !(mc.&methodMissing?.is(helper.getMethodMissingInterceptor()))
        boolean needInstall = !JP_INTERCEPTED.contains(clazz) || missingInvoke || missingMissing

        if (needInstall) {
            mc.invokeMethod = helper.getMethodInterceptor()
            mc.static.invokeMethod = helper.getMethodInterceptor()
            mc.static.methodMissing = helper.getMethodMissingInterceptor()
            mc.methodMissing = helper.getMethodMissingInterceptor()
            mc.getEnv = { return binding.env }
            binding.variables.forEach { String property, Object value ->
                mc."$property" = mc."$property" ?: value
            }
            mc.methods.forEach { scriptMethod ->
                def signature = method(scriptMethod.name, scriptMethod.nativeParameterTypes)
                Map.Entry<MethodSignature, Closure> matchingMethod = helper.allowedMethodCallbacks.find { k, v -> k == signature }
                if (matchingMethod) {
                    mc."$scriptMethod.name" = matchingMethod.value ?: defaultClosure(matchingMethod.key.args)
                }
            }
            JP_INTERCEPTED.add(clazz)
        }
        // Ensure the passed metaClazz (possibly instance-level) also has hooks if distinct
        if (metaClazz.is(mc) == false) {
            boolean metaNeeds = true
            try {
                metaNeeds = !(metaClazz.invokeMethod?.is(helper.getMethodInterceptor())) || !(metaClazz.&methodMissing?.is(helper.getMethodMissingInterceptor()))
            } catch(ignore) {}
            if (metaNeeds) {
                if (!(metaClazz instanceof ExpandoMetaClass)) {
                    def emc = new ExpandoMetaClass(clazz, true, true)
                    emc.initialize()
                    metaClazz = emc
                }
                metaClazz.invokeMethod = helper.getMethodInterceptor()
                metaClazz.static.invokeMethod = helper.getMethodInterceptor()
                metaClazz.static.methodMissing = helper.getMethodMissingInterceptor()
                metaClazz.methodMissing = helper.getMethodMissingInterceptor()
            }
        }
    }
    private static boolean shouldInstrument(Class c) {
        return shouldInstrument(c.name)
    }

    private static boolean shouldInstrument(String n) {
        if (n.startsWith('java.') || n.startsWith('javax.') ||
                n.startsWith('groovy.') || n.startsWith('org.codehaus.groovy.') ||
                n.startsWith('org.apache.') || n.startsWith('org.w3c.') ||
                n.startsWith('org.xml.') || n.startsWith('com.cloudbees')) return false
        return true
    }

    static Closure defaultClosure(Class[] args) {
        int maxLength = 254
        if (args.length > maxLength) {
            throw new IllegalArgumentException("Only $maxLength arguments allowed")
        }
        String argumentsString = args.inject("") { acc, value ->
            return "${acc}${value.name} ${'a' * (acc.count(',') + 1)},"
        }
        String argumentsStringWithoutComma = argumentsString.size() > 0 ?
                argumentsString.substring(0, argumentsString.length() - 1) : argumentsString
        String closureString = "{$argumentsStringWithoutComma -> }"
        return (Closure) new GroovyShell().evaluate(closureString)
    }

    PipelineTestHelper helper
    Binding binding

    InterceptingGCL(PipelineTestHelper helper,
                    ClassLoader loader,
                    CompilerConfiguration config,
                    Binding binding) {
        super(loader, config)
        this.helper = helper
        this.binding = binding
    }

    @Override
    Class parseClass(final String text, final String fileName) throws CompilationFailedException {
        Class clazz = super.parseClass(content, path)
        interceptClassMethods(clazz.metaClass, helper, binding)
        return clazz
    }

    @Override
    Class parseClass(GroovyCodeSource codeSource, boolean shouldCacheSource)
            throws CompilationFailedException {
        Class clazz = super.parseClass(codeSource, shouldCacheSource)

        interceptClassMethods(clazz.metaClass, helper, binding)
        return clazz
    }

    @Override
    Class<?> loadClass(String name) throws ClassNotFoundException {
        // Source from: groovy-all-2.4.6-sources.jar!/groovy/lang/GroovyClassLoader.java:710
        Class cls = null
        // try groovy file
        try {
            URL source = resourceLoader.loadGroovySource(name);
            // if recompilation fails, we want cls==null
            cls = recompile(source, name, null);
        } catch (IOException ioe) {
        } finally {
            if (cls == null) {
                removeClassCacheEntry(name);
            } else {
                setClassCacheEntry(cls);
            }
        }

        if (cls == null) {

            // no class found, using parent's method
            if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("groovy.") || name.startsWith("org.codehaus.groovy.") || name.startsWith("org.apache.") || name.startsWith("org.w3c.") || name.startsWith("org.xml.") || name.startsWith("com.lesfurets.")) {
                return super.loadClass(name);
            } else {
                cls = super.loadClass(name)
            }
        }
        interceptClassMethods(cls.metaClass, helper, binding)
        // Copy from this.parseClass(GroovyCodeSource, boolean)
        return cls;
    }
}