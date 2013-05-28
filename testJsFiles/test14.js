goog.provide('camp');


/**
 * @constructor
 * @param {Test.ParamType} conf
 */
function Test(conf) {
  /**
   * @private {string}
   */
  this.name1_ = conf.loginUserName;
  /**
   * @private {string}
   */
  this.name2_ = conf.mailAddress;

  /**
   * @private {Test2}
   */
  this._test2 = conf.test2;
}

/**
 * @typedef {{loginUserName : string, mailAddress : string, test2 : Test2}}
 */
Test.ParamType;


/**
 * @return {string}
 */
Test.prototype.getUserInfo = function() {
  return this.name1_ + ':' + this.name2_ + ":" + this._test2.getUserAge();
};


/**
 * @constructor
 * @param {string} userAge
 */
function Test2(userAge) {
  this._userAge = userAge;
}


/**
 * @returns {string}
 */
Test2.prototype.getUserAge = function() {
  return this._userAge;
};


/**
 * @constructor
 */
function Config1() {
  /**
   * @private {string}
   */
  this._name = 'userName';

  /**
   * @private {string}
   */
  this._mailAddress = 'mailAddress';
};



/**
 * @return {Test.ParamType}
 */
Config1.prototype.conf = function() {
  return {
    loginUserName : this._name,
    mailAddress : this._mailAddress,
    test2 : camp.utils.dependencies.inject(Test2, this)
  };
};


/**
 * @constructor
 */
function Config2() {

}

/**
 * @return {string}
 */
Config2.prototype.userAge = function () {
  return '12';
};

camp = {
  /**
   * @param {Function} a
   * @param {Function} b
   * @param {Object=} opt_rename
   */
  mixin : function(a, b, opt_rename){
    if (opt_rename) {
      for (var prop in opt_rename) {
        a.prototype[prop] = opt_rename[prop];
      }
    }
    for (prop in b.prototype) {
      if (!(prop in a.prototype)) {
        a.prototype[prop] = b.prototype[prop];
      }
    }
  }
};

camp.mixin(Config1, Config2);


/**
 * @constructor
 */
function TestBuilder() {
  /**
   * @private {Config1}
   */
  this._componentRegistry = new Config1;

}

/**
 * @return {Test}
 */
TestBuilder.prototype.newInstance = function() {
  return camp.utils.dependencies.inject(Test, this._componentRegistry);
};


var test = new TestBuilder().newInstance();
var userInfo = test.getUserInfo();
document.getElementById('aaa').innerHTML = userInfo;
document.getElementById('baaa').innerHTML = userInfo;
