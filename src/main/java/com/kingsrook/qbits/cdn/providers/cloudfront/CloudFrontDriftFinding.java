/*
 * Copyright 2026 Kingsrook, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kingsrook.qbits.cdn.providers.cloudfront;


/*******************************************************************************
 ** One difference between the live distribution and the OpenTofu
 ** expected_config output.
 **
 ** @param area     what differs, such as "aliases", "origin.port",
 **                 "origin.header:X-Origin-Verify", "behavior:/api/*"
 **                 or "never_cache:/v1/me*"
 ** @param expected what tofu says, as text
 ** @param actual   what CloudFront has, as text ("(missing)" when absent)
 *******************************************************************************/
public record CloudFrontDriftFinding(String area, String expected, String actual)
{
}
