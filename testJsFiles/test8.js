var testNs = {foo:{bar:{}}};
/**
 * @constructor
 * @param {testNs.foo.bar.TestDeps} testDeps
 */
testNs.foo.bar.Test = function(testDeps){this.testDeps = testDeps;};

testNs.foo.bar.Test.prototype.toString = function() {
  return this.testDeps.foo + '1';
};

/**
 * @constructor
 */
testNs.foo.bar.TestDeps = function(foo){this.foo = foo;};


/**
 * @constructor
 */
function Module() {}

Module.prototype.configure = function() {
  this.foo = 'test';
  /**
   * @return {testNs.foo.bar.TestDeps}
   */
  this.testDeps = function(foo) {return new testNs.foo.bar.TestDeps(foo)};
  return this;
};
(function(){
  var module = (new Module).configure();
  var test = new testNs.foo.bar.Test(module.testDeps(module.foo));
  document.body.innerHTML = test;
})();