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
package com.camundacon.tutorial.testing.bpmn;

import static org.camunda.bpm.engine.test.assertions.ProcessEngineTests.*;
import static org.assertj.core.api.Assertions.*;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.externaltask.ExternalTask;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.camunda.bpm.engine.impl.util.ClockUtil;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.runtime.ProcessInstanceQuery;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.engine.test.Deployment;
import org.camunda.bpm.engine.test.ProcessEngineRule;
import org.camunda.bpm.engine.test.mock.Mocks;
import org.camunda.bpm.engine.variable.Variables;
import org.camunda.bpm.extension.process_test_coverage.junit.rules.TestCoverageProcessEngineRuleBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.camundacon.tutorial.testing.api.BookingService;
import com.camundacon.tutorial.testing.api.MessageService;
import com.camundacon.tutorial.testing.delegate.CancelSeatsDelegate;
import com.camundacon.tutorial.testing.delegate.CheckSeatsDelegate;
import com.camundacon.tutorial.testing.delegate.SendMailDelegate;
import com.camundacon.tutorial.testing.delegate.SendWhatsappDelegate;

/**
 * Tests the "next_match.bpmn", "plan_event_ride.bpmn", and "cancel_booking.bpmn"
 */
@RunWith(MockitoJUnitRunner.class) // initializes all necessary @Mock fields
@Deployment(resources = { "next_match.bpmn", "plan_event_ride.bpmn", "cancel_booking.bpmn" })
public class TestNextMatch {

  private static final String PROCESS_KEY = "organize_next_match_visit";

  @Rule
  @ClassRule
  public static ProcessEngineRule engineRule = TestCoverageProcessEngineRuleBuilder.create().build();

  // -----------------
  //   PREPARE MOCKS
  // -----------------

  // mock the services which can be configured in the tests if needed
  @Mock
  private BookingService bookingService;
  @Mock
  private MessageService messageService;

  // inject the services into the delegates
  @InjectMocks
  private CancelSeatsDelegate cancelSeatsDelegate;
  @InjectMocks
  private CheckSeatsDelegate checkSeatsDelegate;
  @InjectMocks
  private SendMailDelegate sendMailDelegate;
  @InjectMocks
  private SendWhatsappDelegate sendWhatsappDelegate;

  @Before
  public void setup() {
    // register the delegates
    Mocks.register("cancelSeatsDelegate", cancelSeatsDelegate);
    Mocks.register("checkSeatsDelegate", checkSeatsDelegate);
    Mocks.register("sendMailDelegate", sendMailDelegate);
    Mocks.register("sendWhatsappDelegate", sendWhatsappDelegate);
  }

  @After
  public void tearDown() {
    // reset all mocks and delete the deployments (including running instances)
    Mocks.reset();
    cleanDeployments();
  }

  // --------------
  //   HAPPY PATH
  // --------------

  @Test
  public void shouldFinishNextMatchPlanning() {
    // first blue
    ProcessInstance instance = runtimeService().startProcessInstanceByKey(PROCESS_KEY);
    assertThat(instance)
      .isActive()
      .isWaitingAtExactly("pick_next_event")
      .task().isNotAssigned();
    complete(task(), withVariables(
        "assignee", "tobias",
        "eventLocation", "away",
        "eventName", "dortmund-vs-schalke"));
    // orange
    assertThat(instance)
      .hasPassed("inform_away_group")
      .isWaitingAtExactly("reserve_seats");
    // green
    complete(externalTask(), withVariables("orderId", "JFHK-837K-JHFU"));
    assertThat(instance)
      .isWaitingAtExactly("wait_on_answers");
    // red
    runtimeService()
      .createMessageCorrelation("Message_group_accepts")
      .setVariable("participants", 8)
      .correlate();
    assertThat(instance)
      .isWaitingAtExactly("adjust_and_confirm_seats")
      .task().isAssignedTo("tobias");
    // second blue
    complete(task());
    assertThat(instance)
      .isWaitingAtExactly("plan_event_ride_ca");
    // purple
    ProcessInstance calledProcessInstance = calledProcessInstance(instance);
    assertThat(calledProcessInstance)
      .isActive()
      .isWaitingAtExactly("plan_event_ride_task");
    complete(task(calledProcessInstance));
    assertThat(calledProcessInstance)
      .isEnded();
    // process end
    assertThat(instance)
      .hasPassedInOrder("plan_event_ride_ca", "end_go_team")
      .isEnded();
  }

  //-------------------------
  //        PICK EVENT
  // -------------------------

  @Test
  public void vanilla_shouldCompletePickNextEvent() {
    // given
    RuntimeService runtimeService = engineRule.getRuntimeService();
    TaskService taskService = engineRule.getTaskService();
    ProcessInstance instance = runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    // assume
    assertThat(runtimeService.createProcessInstanceQuery().count()).isEqualTo(1L);
    assertThat(runtimeService.getActiveActivityIds(instance.getId())).containsExactly("pick_next_event");
    assertThat(taskService.createTaskQuery().count()).isEqualTo(1L);
    Task task = taskService.createTaskQuery().singleResult();
    assertThat(task.getAssignee()).isNull();
    // when
    taskService.complete(task.getId(), Variables.putValue("assignee", "tobias")
        .putValue("eventLocation", "away")
        .putValue("eventName", "dortmund-vs-schalke"));
    // then
    assertThat(runtimeService.getActiveActivityIds(instance.getId())).containsExactly("reserve_seats");
  }

  @Test
  public void shouldCompletePickNextEvent() {
    // given
    ProcessInstance instance = runtimeService().startProcessInstanceByKey(PROCESS_KEY);
    // assume
    assertThat(instance)
      .isWaitingAtExactly("pick_next_event")
      .task()
        .isNotAssigned();
    // when
    complete(task(), withVariables(
        "assignee", "tobias",
        "eventLocation", "away",
        "eventName", "dortmund-vs-schalke"));
    // then
    assertThat(instance).isWaitingAtExactly("reserve_seats");
  }

  // -------------------------
  //      INFORM GROUP
  // -------------------------

  /**
   * NOT RECOMMENDED:
   * Do not step through the whole process until you arrive at the
   * position you want to test, rather use process instance modification
   * in order to be independent from all changes to the model prior to
   * the chunk of the model you want to test
   */
  @Test
  public void DONT_shouldSendWhatsappForHomeGroup() {
    // given
    ProcessInstance instance = runtimeService().startProcessInstanceByKey(PROCESS_KEY);
    assertThat(instance)
      .isActive()
      .isWaitingAt("pick_next_event")
      .task().isNotAssigned();
    // when
    complete(task(), withVariables("eventLocation", "home"));
    // then
    assertThat(instance)
      .hasPassed("inform_home_group")
      .isWaitingAt("reserve_seats");
  }

  @Test
  public void shouldSendWhatsappForHomeGroup() {
    // given
    ProcessInstance instance = runtimeService()
        .createProcessInstanceByKey(PROCESS_KEY)
        .startTransition("move_to_choose_group")
        // when
        .setVariables(withVariables("eventLocation", "home"))
        .execute();
    // then
    assertThat(instance)
      .hasPassed("inform_home_group")
      .isWaitingAt("reserve_seats");
  }

  //------------------------
  //      RESERVE SEATS
  // ------------------------

  @Test
  public void vanilla_shouldReserveSeatsExternally() {
    // given
    RuntimeService runtimeService = engineRule.getRuntimeService();
    TaskService taskService = engineRule.getTaskService();
    ExternalTaskService externalTaskService = engineRule.getExternalTaskService();

    ProcessInstance instance = runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    Task task = taskService.createTaskQuery().singleResult();
    taskService.complete(task.getId(), Variables.putValue("eventLocation", "away"));
    // assume
    assertThat(runtimeService.getActiveActivityIds(instance.getId())).containsExactly("reserve_seats");
    assertThat(externalTaskService.createExternalTaskQuery().count()).isEqualTo(1L);
    ExternalTask externalTask = externalTaskService.createExternalTaskQuery().singleResult();
    // when
    List<LockedExternalTask> lockedTasks = externalTaskService.fetchAndLock(1, "aWorker")
      .topic(externalTask.getTopicName(), 30000L)
      .execute();
    assertThat(lockedTasks.size()).isEqualTo(1L);
    assertThat(lockedTasks.get(0).getId()).isEqualTo(externalTask.getId());
    externalTaskService.complete(lockedTasks.get(0).getId(), "aWorker",
        Variables.putValue("orderId", "JFHK-837K-JHFU"));
    // then
    assertThat(runtimeService.getActiveActivityIds(instance.getId())).containsExactly("wait_on_answers");
  }

  @Test
  public void shouldReserveSeatsExternally() {
    // given
    ProcessInstance instance = runtimeService().createProcessInstanceByKey(PROCESS_KEY)
        .startBeforeActivity("reserve_seats")
        .execute();
    // assume
    assertThat(instance).isWaitingAtExactly("reserve_seats");
    // when
    complete(externalTask(), withVariables("orderId", "JFHK-837K-JHFU"));
    // then
    assertThat(instance).isWaitingAtExactly("wait_on_answers");
  }

  // ------------------------
  //      DIGEST ANSWERS
  // ------------------------

  @Test
  public void shouldCreateCancelSeatsJobOnGroupDecline() {
    // given
    ProcessInstance instance = runtimeService()
        .createProcessInstanceByKey(PROCESS_KEY)
        .startTransition("move_to_wait_on_answers")
        .execute();
    // when
    runtimeService()
        .createMessageCorrelation("Message_group_declines")
        .correlate();
    // then
    assertThat(instance)
      .isActive()
      .isWaitingAt("cancel_seats")
      .job("cancel_seats").isNotNull();
  }

  @Test
  public void shouldCreateCancelSeatsJobWhenTimerIsExceeded() {
    // given
    ProcessInstance instance = runtimeService()
        .createProcessInstanceByKey(PROCESS_KEY)
        .startTransition("move_to_wait_on_answers")
        .execute();
    // timer job is there but not activated
    assertThat(jobQuery().count()).isEqualTo(1L);
    assertThat(jobQuery().executable().count()).isEqualTo(0L);
    // when (move the clock to activate it and run it)
    ClockUtil.offset(TimeUnit.DAYS.toMillis(4L));
    assertThat(jobQuery().executable().count()).isEqualTo(1L);
    execute(jobQuery().executable().singleResult());
    // then
    assertThat(instance)
      .isActive()
      .isWaitingAt("cancel_seats")
      .job("cancel_seats").isNotNull();
  }

  @Test
  public void shouldCancelProcessWhenCancelSeatsJobExecutes() {
    // given
    ProcessInstance instance = runtimeService()
        .createProcessInstanceByKey(PROCESS_KEY)
        .startTransition("move_to_cancel_seats")
        .execute();
    assertThat(instance)
      .isActive()
      .isWaitingAt("cancel_seats");
    assertThat(jobQuery().executable().count()).isEqualTo(1L);
    // when
    execute(job());
    // then
    assertThat(instance)
      .hasPassed("cancel_seats", "end_decline")
      .isEnded();
  }

  // -----------------------------
  //        CHECK SEATS
  // -----------------------------

  @Test
  public void shouldCheckSeatsWhenTimerExceeds() {
    // given
    Mockito.when(bookingService.checkSeats(Mockito.anyString())).thenReturn(true);
    ProcessInstance instance = runtimeService()
          .createProcessInstanceByKey(PROCESS_KEY)
          .startBeforeActivity("adjust_and_confirm_seats")
          .setVariables(withVariables(
              "assignee", "test",
              "orderId", "TEST-ORDER-ID"))
          .execute();
    Date expectedDueDate = new Date(ClockUtil.getCurrentTime().getTime() + TimeUnit.HOURS.toMillis(12L));
    assertThat(instance)
      .isWaitingAt("adjust_and_confirm_seats")
      .job().hasActivityId("adjust_and_confirm_seats");
    assertThat(jobQuery().executable().count()).isEqualTo(0L);
    assertThat(job().getDuedate()).isEqualToIgnoringSeconds(expectedDueDate);
    // when (move the clock to activate it and run it)
    ClockUtil.offset(TimeUnit.HOURS.toMillis(13L));
    assertThat(jobQuery().executable().count()).isEqualTo(1L);
    execute(job());
    // then
    assertThat(instance)
      .isWaitingAt("adjust_and_confirm_seats")
      .hasPassed("end_available");
    assertThat(jobQuery().executable().count()).isEqualTo(0L);
    Mockito.verify(bookingService).checkSeats("TEST-ORDER-ID");
    Mockito.verifyNoMoreInteractions(bookingService);
  }

  @Test
  public void shouldTerminateProcessWhenSeatsNotAvailable() {
    // given
    Mockito.when(bookingService.checkSeats(Mockito.anyString())).thenReturn(false);
    ProcessInstance instance = runtimeService()
          .createProcessInstanceByKey(PROCESS_KEY)
          .startBeforeActivity("adjust_and_confirm_seats")
          .setVariables(withVariables(
              "assignee", "test",
              "orderId", "TEST-ORDER-ID"))
          .execute();
    assertThat(instance)
      .isWaitingAt("adjust_and_confirm_seats")
      .job().hasActivityId("adjust_and_confirm_seats");
    // when
    execute(job());
    // then
    assertThat(instance)
      .hasPassed("end_unavailable")
      .isEnded();
    Mockito.verify(bookingService).checkSeats("TEST-ORDER-ID");
    Mockito.verifyNoMoreInteractions(bookingService);
  }

  // -----------------------------
  //        PLAN EVENT RIDE
  // -----------------------------

  @Test
  public void vanilla_shouldPlanEventRide() {
    // given
    RuntimeService runtimeService = engineRule.getRuntimeService();
    TaskService taskService = engineRule.getTaskService();
    ExternalTaskService externalTaskService = engineRule.getExternalTaskService();
    HistoryService historyService = engineRule.getHistoryService();

    ProcessInstance instance = runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    Task task = taskService.createTaskQuery().singleResult();
    taskService.complete(task.getId(), Variables.putValue("eventLocation", "away")
        .putValue("assignee", "tobias"));

    ExternalTask externalTask = externalTaskService.createExternalTaskQuery().singleResult();
    List<LockedExternalTask> lockedTasks = externalTaskService.fetchAndLock(1, "aWorker")
        .topic(externalTask.getTopicName(), 30000L)
        .execute();
    externalTaskService.complete(lockedTasks.get(0).getId(), "aWorker");

    runtimeService()
      .createMessageCorrelation("Message_group_accepts")
      .correlate();

    task = taskService.createTaskQuery().singleResult();
    taskService.complete(task.getId());
    // assume
    ProcessInstanceQuery calledProcessInstanceQuery = runtimeService.createProcessInstanceQuery()
        .superProcessInstanceId(instance.getId());
    ProcessInstance calledProcessInstance = calledProcessInstanceQuery.singleResult();
    assertThat(runtimeService.getActiveActivityIds(calledProcessInstance.getId()))
      .containsExactly("plan_event_ride_task");
    assertThat(taskService.createTaskQuery().processInstanceId(calledProcessInstance.getId()).count()).isEqualTo(1L);
    task = taskService.createTaskQuery().processInstanceId(calledProcessInstance.getId()).singleResult();
    // when
    taskService.complete(task.getId());
    // then
    assertThat(historyService.createHistoricProcessInstanceQuery()
        .processInstanceId(calledProcessInstance.getId())
        .completed()
        .count()).isEqualTo(1L);
    assertThat(historyService.createHistoricProcessInstanceQuery()
        .processInstanceId(instance.getId())
        .completed()
        .count()).isEqualTo(1L);
  }

  @Test
  public void shouldPlanEventRide() {
    // given
    ProcessInstance instance = runtimeService()
        .createProcessInstanceByKey(PROCESS_KEY)
        .startBeforeActivity("plan_event_ride_ca")
        .execute();
    ProcessInstance calledProcessInstance = calledProcessInstance(instance);
    // assume
    assertThat(calledProcessInstance)
      .isActive()
      .isWaitingAtExactly("plan_event_ride_task");
    // when
    complete(task(calledProcessInstance));
    // then
    assertThat(calledProcessInstance)
      .isEnded();
    assertThat(instance)
    .hasPassedInOrder("plan_event_ride_ca", "end_go_team")
    .isEnded();
  }

  // --------------------------
  //   CONSUME CANCEL MESSAGE
  // --------------------------

  @Test
  public void shouldTerminateProcessOnCancelMessageWhenSeatsNotConfirmed() {
    // given
    ProcessInstance instance = runtimeService()
        .createProcessInstanceByKey(PROCESS_KEY)
        .startBeforeActivity("adjust_and_confirm_seats")
        .setVariables(withVariables(
            "assignee", "test",
            "orderId", "TEST-ORDER-ID"))
        .execute();
    // when
    runtimeService().correlateMessage("Message_event_canceled");
    // then
    assertThat(instance)
      .hasPassedInOrder("start_message_cancel", "end_throw_cancel_seats",
          "start_catch_cancel_seats", "cancel_seats_escalation", "end_cancel_seats")
      .isEnded();
  }

  @Test
  public void shouldCancelBookingOnCancelMessageWhenSeatsConfirmed() {
    // given
    ProcessInstance instance = runtimeService()
        .createProcessInstanceByKey(PROCESS_KEY)
        .startBeforeActivity("plan_event_ride_ca")
        .setVariables(withVariables(
            "seatsConfirmed", true,
            "orderId", "TEST-ORDER-ID"))
        .execute();
    // when
    runtimeService().correlateMessage("Message_event_canceled");
    // then
    assertThat(instance)
      .hasPassed("start_message_cancel", "end_throw_cancel_booking", "start_catch_cancel_booking")
      .isWaitingAt("plan_event_ride_ca")
      .calledProcessInstance("cancel_booking")
      .isWaitingAt("cancel_booking_task");
  }

  @Test
  public void shouldNotInterruptProcessOnCancelMessageWhenSeatsConfirmed() {
    // given
    ProcessInstance instance = runtimeService()
        .createProcessInstanceByKey(PROCESS_KEY)
        .startBeforeActivity("plan_event_ride_ca")
        .setVariables(withVariables(
            "seatsConfirmed", true,
            "orderId", "TEST-ORDER-ID"))
        .execute();
    runtimeService().correlateMessage("Message_event_canceled");
    assertThat(instance)
      .isWaitingAt("plan_event_ride_ca")
      .calledProcessInstance("cancel_booking")
      .isWaitingAt("cancel_booking_task");
    // when
    ProcessInstance calledProcessInstance = calledProcessInstance("cancel_booking");
    complete(task(calledProcessInstance));
    // then
    assertThat(calledProcessInstance)
      .isEnded();
    assertThat(instance)
      .isWaitingAt("plan_event_ride_ca");
  }

  // -----------
  //   HELPERS
  // -----------

  protected void cleanDeployments() {
    for (org.camunda.bpm.engine.repository.Deployment deployment : repositoryService().createDeploymentQuery().list()) {
      repositoryService().deleteDeployment(deployment.getId(), true);
    }
  }

}
