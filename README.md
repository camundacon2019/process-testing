CamundaCon 2019 Process Testing Example
=================================

Code examples for testing complex aspects of processes using Camunda BPM.


How to use it
-----------------------------

1. Checkout the project with Git
2. Import the project into your IDE
3. Inspect the [test class](./src/test/java/com/camundacon/tutorial/testing/bpmn/TestNextMatch.java) 
4. Build it with maven by `mvn clean package`
5. Inspect the visual test results in `target/process-test-coverage/`
6. Deploy it to a camunda-bpm-platform distro of your own choice, that supports EJB's (Wildfly, Jboss)
7. Start an instance of the process `Organize next match visit` (process key `organize_next_match_visit`)

Misc
-----------------------------

Feel free to watch the accompanying video:
[![Watch the video](https://img.youtube.com/vi/gZF1kArXaa8/maxresdefault.jpg)](https://youtu.be/gZF1kArXaa8)
