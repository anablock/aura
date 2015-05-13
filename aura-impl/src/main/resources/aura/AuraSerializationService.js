/*
 * Copyright (C) 2013 salesforce.com, inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/**
 * @description The Aura Serialization Service.  Serializes and Deserializes Aura Entities appropriately.
 * @constructor
 */
var AuraSerializationService = function(){

    return {
        readComponent : function(config){
            var newCmpDeprecated = "newComponentDeprecated";
            $A.Perf.mark("resolvedRefs");
            $A.Perf.mark("constructedComponent");
            config = aura.util.json.resolveRefs(config);


            $A.Perf.endMark("resolvedRefs");
            // we don't call the method directly in order to always 
            // use the public componentService public funtion (in prod will be mangled so no string)
            var ret = $A.componentService[newCmpDeprecated](config, null, false, true);
            $A.Perf.endMark("constructedComponent");
            return ret;
        }
    };
};

Aura.Services.AuraSerializationService = AuraSerializationService;