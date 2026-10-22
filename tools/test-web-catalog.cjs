const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync('app/src/main/java/com/spxiaolong/gofilm/WebCatalogTransport.java', 'utf8');
const start = source.indexOf('String script = ') + 'String script = '.length;
const end = source.indexOf(';\n        browser.evaluateJavascript(script', start);
assert.ok(start > 0 && end > start);

// Evaluate the exact JavaScript literals emitted by the Java transport, rather
// than maintaining a second implementation of its request protocol.
function expression(path) {
  const tokens = source.slice(start, end).match(/"(?:\\.|[^"\\])*"|JSONObject\.quote\(path\)|\bkey\b/g);
  return tokens.map(token => token === 'key' ? JSON.stringify('request1')
    : token === 'JSONObject.quote(path)' ? JSON.stringify(path) : JSON.parse(token)).join('');
}
function run(scenario, path = '/api/index') {
  class Request {
    open(method, target, async) { this.method = method; this.target = target; assert.equal(async, true); }
    setRequestHeader(name, value) { assert.equal(name, 'Accept'); assert.equal(value, 'application/json'); }
    send() {
      this.status = scenario.status ?? 200;
      this.responseText = scenario.body ?? '{"code":0,"data":{"content":[]}}';
      if (scenario.pending) return;
      if (scenario.network) this.onerror();
      else if (scenario.timeout) this.ontimeout();
      else this.onload();
    }
    abort() { this.aborted = true; }
  }
  const context = vm.createContext({ window: {}, XMLHttpRequest: Request });
  assert.equal(vm.runInContext(expression(path), context), 'started');
  return { context, call: context.window.__tvCalls.request1 };
}
const ok = run({});
assert.equal(ok.call.done, true);
assert.equal(JSON.parse(ok.call.body).code, 0);
assert.equal(ok.call.xhr.target, '/api/index');
assert.equal(run({status:503}).call.error, '浏览器接口 HTTP 503');
assert.equal(run({network:true}).call.error, '浏览器连接失败（XHR）');
assert.equal(run({timeout:true}).call.error, '浏览器接口请求超时');
assert.equal(run({body:'x'.repeat(3145729)}).call.error, '接口返回内容过大');
assert.equal(run({pending:true}).call.done, false);
const hostile = '/api/searchFilm?keyword=";window.injected=true;//';
const quoted = run({}, hostile);
assert.equal(quoted.call.xhr.target, hostile);
assert.equal(quoted.context.window.injected, undefined);
vm.runInContext('var c=window.__tvCalls["request1"];c.xhr.abort();delete window.__tvCalls["request1"];', ok.context);
assert.equal(ok.call.xhr.aborted, true);
assert.equal(ok.context.window.__tvCalls.request1, undefined);
console.log('Web catalogue transport checks passed');
