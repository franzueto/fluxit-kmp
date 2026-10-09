// Webpack writes `import.meta.url` into a non-module bundle as the module's file:// path on
// the build machine (Skiko's loader reads it), which would publish local paths with the web
// bundle. Replace it with the module's file name under the runtime public path, which is
// where the file is served from. `new URL("x", import.meta.url)` asset references are
// handled separately by webpack and are not affected. Only `import.meta.url` itself is
// rewritten; destructuring (`const { url } = import.meta`) would still get the disk path,
// which the build's guard below would then reject.
// Checked by checkWebDistributionForLocalPaths in composeApp/build.gradle.kts.
;(function (config) {
    const name = 'FluxItImportMetaUrl';
    config.plugins.push({
        apply(compiler) {
            const { RuntimeGlobals } = compiler.webpack;
            const { ConstDependency } = compiler.webpack.dependencies;
            const path = require('path');
            compiler.hooks.compilation.tap(name, (compilation, { normalModuleFactory }) => {
                const handler = (parser) => {
                    // Runs before webpack's own ImportMetaPlugin (stage 0), which would bail with the path.
                    parser.hooks.expression.for('import.meta.url').tap({ name, stage: -10 }, (expression) => {
                        const fileName = JSON.stringify(path.basename(parser.state.module.resource));
                        const dependency = new ConstDependency(
                            `(${RuntimeGlobals.publicPath} + ${fileName})`,
                            expression.range,
                            [RuntimeGlobals.publicPath],
                        );
                        dependency.loc = expression.loc;
                        parser.state.module.addPresentationalDependency(dependency);
                        return true;
                    });
                };
                for (const type of ['javascript/auto', 'javascript/esm']) {
                    normalModuleFactory.hooks.parser.for(type).tap(name, handler);
                }
            });
        },
    });
})(config);
