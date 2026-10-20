// Loads the bundled Sucrase in a bare JavaScript context (no Node globals, like QuickJS) and checks that JSX and TypeScript compile.
const fs = require('fs'), vm = require('vm');
const file = process.argv[2];
const context = vm.createContext({});
vm.runInContext(fs.readFileSync(file, 'utf8'), context, { filename: file });
function run(source, transforms) {
  context.__source = source; context.__transforms = transforms;
  return vm.runInContext("Sucrase.transform(__source, {transforms: __transforms, jsxRuntime: 'classic', production: true}).code", context);
}
const jsx = run('const App = () => <div className="x">hi</div>;', ['jsx']);
if (!jsx.includes('React.createElement')) { console.error('JSX not transformed:\n' + jsx); process.exit(1); }
const ts = run('const n: number = 1; interface A { b: string }', ['typescript']);
if (ts.includes('interface') || ts.includes(': number')) { console.error('TypeScript not transformed:\n' + ts); process.exit(1); }
console.log('sucrase bundle OK (' + fs.statSync(file).size + ' bytes)');
