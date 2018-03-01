({
    browsers: ['-IE8', '-IE9', '-IE10', '-IE11', '-FIREFOX', '-SAFARI', '-IPHONE', '-IPAD'],

    setUp: function(cmp) {
        cmp.set('v.testUtils', $A.test);
    },
    
    _testSecureEvalIsLockerized: {
      test: function(component) {
          component.testSecureEvalIsLockerized(component);
      }
    },
    
    _testGlobalIntrinsics: {
        test: function(component) {
            component.testGlobalIntrinsics(component);
        }
    },
    
    _testHiddenIntrinsics: {
        test: function(component) {
            component.testHiddenIntrinsics(component);
        }
    }
})