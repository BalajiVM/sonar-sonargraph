/*
 * Sonar Sonargraph Plugin
 * Copyright (C) 2009, 2010, 2011 hello2morrow GmbH
 * mailto: info AT hello2morrow DOT com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hello2morrow.sonarplugin.api;

import com.hello2morrow.sonarplugin.foundation.Java;
import com.hello2morrow.sonarplugin.foundation.SonargraphPluginBase;
import com.hello2morrow.sonarplugin.foundation.TestHelper;
import com.hello2morrow.sonarplugin.metric.SonargraphSimpleMetrics;
import org.junit.Before;
import org.junit.Test;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.batch.fs.FilePredicate;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.profiles.RulesProfile;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.Qualifiers;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SonargraphSensorTest extends AbstractSonargraphTest {

  public static final String REPORT = "src/test/resources/sonargraph-sonar-report.xml";
  private SonargraphSensor sensor;

  @Override
  protected String getReport() {
    return REPORT;
  }

  @Before
  public void initSensor() {
    sensor = new SonargraphSensor(getRulesProfile(), getSettings(), getSensorContext(), getModuleFileSystem(), TestHelper.initPerspectives());
  }

  @Test
  public void testAnalyseRootParentProject() {
    Project rootProject = new Project("hello2morrow:AlarmClock");
    Project module = new Project("module");
    module.setParent(rootProject);
    SensorContext context = mock(SensorContext.class);
    when(context.getMeasure(SonargraphSimpleMetrics.INTERNAL_PACKAGES)).thenReturn(null);

    sensor.analyse(null, context);
    assertNull(context.getMeasure(SonargraphSimpleMetrics.INTERNAL_PACKAGES));
  }

  @Test
  public void testAnalyse() {
    Project project = mock(Project.class); // ("hello2morrow:AlarmClock", "", "AlarmClock");
    doReturn("hello2morrow:AlarmClock").when(project).key();
    doReturn("AlarmClock").when(project).name();
    doReturn(Qualifiers.MODULE).when(project).getQualifier();

    FileSystem projectFileSystem = mock(FileSystem.class);
    File baseDir = new File("src/test/resources");
    when(getModuleFileSystem().baseDir()).thenReturn(baseDir);
    File sourceFile = new File(baseDir, "com/hello2morrow/sonarplugin/Test.java");
    when(projectFileSystem.baseDir()).thenReturn(baseDir);

    when(getModuleFileSystem().files(any(FilePredicate.class))).thenReturn(Arrays.asList(sourceFile));

    sensor.analyse(project, getSensorContext());
    double value = getSensorContext().getMeasure(SonargraphSimpleMetrics.WORKSPACE_WARNINGS).getValue().doubleValue();
    assertEquals(0.0, value, 0.01);
  }

  @Test
  public void testShouldExecuteOnProject() {
    Project project = new Project("hello2morrow:AlarmClock", "", "AlarmClock");
    project.setLanguage(new Java());
    assertTrue(sensor.shouldExecuteOnProject(project));

    Project module = new Project("hello2morrow:Foundation", "", "Foundation");
    module.setParent(project);
    module.setLanguage(new Java());
    assertFalse(sensor.shouldExecuteOnProject(project));
    assertTrue(sensor.shouldExecuteOnProject(module));
  }

  @Test
  public void testShouldNotExecuteOnProject() {
    RulesProfile rulesProfile = RulesProfile.create(SonargraphPluginBase.PLUGIN_KEY, "JAVA");
    this.sensor = new SonargraphSensor(rulesProfile, getSettings(), getSensorContext(), getModuleFileSystem(), TestHelper.initPerspectives());
    Project project = new Project("hello2morrow:AlarmClock", "", "AlarmClock");
    project.setLanguage(new Java());
    assertFalse("Sensor should not execute because neither sonargraph rules are active, nor alerts are defined for sonargraph rules", this.sensor.shouldExecuteOnProject(project));
  }

  @Test
  public void testShouldNotExecuteOnProjectWithoutReport() {
    Project project = new Project("hello2morrow:AlarmClock", "", "AlarmClock");
    project.setLanguage(new Java());
    Project module = new Project("hello2morrow:Foundation", "", "Foundation");
    module.setParent(project);
    module.setLanguage(new Java());
    getSettings().setProperty(SonargraphPluginBase.REPORT_PATH, "c:/fantasyPath");
    assertFalse("Sensor must not execute on aggregating project", sensor.shouldExecuteOnProject(project));
    assertFalse("Sensor must not execute on module without report", sensor.shouldExecuteOnProject(module));
  }

  @Test
  public void testToString() {
    SonargraphSensor sensor = new SonargraphSensor(RulesProfile.create(SonargraphPluginBase.PLUGIN_KEY, "JAVA"), getSettings(), getSensorContext(), getModuleFileSystem(),
      TestHelper.initPerspectives());
    assertTrue("Does not start with plugin name", sensor.toString().startsWith(SonargraphSensor.PLUGIN_NAME));
    assertTrue("Does not contain version info", sensor.toString().length() > (SonargraphSensor.PLUGIN_NAME.length() + 2));
  }
}
