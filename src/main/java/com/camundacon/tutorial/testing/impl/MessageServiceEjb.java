/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
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
package com.camundacon.tutorial.testing.impl;

import javax.ejb.Stateless;
import javax.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.camundacon.tutorial.testing.api.MessageService;

@Named("messageService")
@Stateless
public class MessageServiceEjb implements MessageService {

  private static final Logger LOGGER = LoggerFactory.getLogger(MessageServiceEjb.class.getName());

  @Override
  public void sendMail(String group) {
    LOGGER.info("\n\n\n Sending mail to group {} \n\n\n", group);
  }

  @Override
  public void sendWhatsapp(String group) {
    LOGGER.info("\n\n\n Sending Whatsapp to group {} \n\n\n", group);
  }

}
