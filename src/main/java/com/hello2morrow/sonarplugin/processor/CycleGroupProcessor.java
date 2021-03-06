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
package com.hello2morrow.sonarplugin.processor;

import com.hello2morrow.sonarplugin.foundation.SonargraphPluginBase;
import com.hello2morrow.sonarplugin.foundation.Utilities;
import com.hello2morrow.sonarplugin.persistence.PersistenceUtilities;
import com.hello2morrow.sonarplugin.xsd.ReportContext;
import com.hello2morrow.sonarplugin.xsd.XsdAttributeRoot;
import com.hello2morrow.sonarplugin.xsd.XsdCycleGroup;
import com.hello2morrow.sonarplugin.xsd.XsdCycleGroups;
import com.hello2morrow.sonarplugin.xsd.XsdCyclePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.FilePredicates;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.component.ResourcePerspectives;
import org.sonar.api.issue.Issuable;
import org.sonar.api.issue.Issuable.IssueBuilder;
import org.sonar.api.profiles.RulesProfile;
import org.sonar.api.resources.Directory;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.Resource;
import org.sonar.api.rules.ActiveRule;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CycleGroupProcessor implements IProcessor {

  private static final String PHYSICAL_PACKAGE_NAMED_ELEMENT_GROUP = "Physical package";
  private static final String DIRECTORY_NAMED_ELEMENT_GROUP = "Directory";

  private static final Logger LOG = LoggerFactory.getLogger(CycleGroupProcessor.class);
  private double cyclicity = 0;
  private double biggestCycleGroupSize = 0;
  private double cyclicPackages = 0;
  private final ResourcePerspectives perspectives;
  private final Project project;
  private final ActiveRule rule;
  private final FileSystem fileSystem;
  private String sonargraphBasePath;

  public CycleGroupProcessor(Project project, final RulesProfile rulesProfile, final ResourcePerspectives perspectives, FileSystem fileSystem) {
    this.project = project;
    this.perspectives = perspectives;
    this.rule = rulesProfile.getActiveRule(SonargraphPluginBase.PLUGIN_KEY, SonargraphPluginBase.CYCLE_GROUP_RULE_KEY);
    this.fileSystem = fileSystem;
  }

  public double getCyclicity() {
    return cyclicity;
  }

  public double getBiggestCycleGroupSize() {
    return biggestCycleGroupSize;
  }

  public double getCyclicPackages() {
    return cyclicPackages;
  }

  @Override
  public void process(ReportContext report, XsdAttributeRoot buildUnit) {
    if (rule == null) {
      return;
    }

    this.sonargraphBasePath = PersistenceUtilities.getSonargraphBasePath(report);

    cyclicity = 0;
    biggestCycleGroupSize = 0;
    cyclicPackages = 0;
    boolean packageNotFound = false;

    FilePredicates predicates = fileSystem.predicates();
    XsdCycleGroups cycleGroups = report.getCycleGroups();

    for (XsdCycleGroup group : cycleGroups.getCycleGroup()) {
      if (!PersistenceUtilities.getBuildUnitName(group).equals(Utilities.getBuildUnitName(buildUnit.getName()))) {
        continue;
      }

      String namedElementGroup = group.getNamedElementGroup();
      packageNotFound = !createCycleGroupIssue(predicates, group, namedElementGroup) || packageNotFound;
    }

    if (packageNotFound) {
      LOG.warn("Issues not created for all packages involved in cycles. "
        + "Check if configuration for Sonargraph system includes the generation of cycle warnings for source files and directories!");
    }
  }

  /**
   * @return true if issue is created for all elements, false otherwise
   */
  private boolean createCycleGroupIssue(final FilePredicates predicates, final XsdCycleGroup group, final String namedElementGroup) {
    if (PHYSICAL_PACKAGE_NAMED_ELEMENT_GROUP.equals(namedElementGroup)) {
      int groupSize = group.getCyclePath().size();
      cyclicPackages += groupSize;
      cyclicity += groupSize * groupSize;
      if (groupSize > biggestCycleGroupSize) {
        biggestCycleGroupSize = groupSize;
      }
    } else if (DIRECTORY_NAMED_ELEMENT_GROUP.equals(namedElementGroup) && !handlePackageCycleGroup(group, predicates)) {
      return false;
    } else if ("Source file".equals(namedElementGroup)) {
      handleSourceFileGroup(group);
    }
    return true;
  }

  private void handleSourceFileGroup(XsdCycleGroup group) {
    List<Resource> srcFiles = determineCyclicSrcFiles(group);
    for (Resource srcFile : srcFiles) {
      addCycleIssue(srcFile, srcFiles);
    }
  }

  private List<Resource> determineCyclicSrcFiles(XsdCycleGroup group) {
    List<Resource> srcFiles = new ArrayList<Resource>();
    for (XsdCyclePath pathElement : group.getCyclePath()) {
      String cyclicFilePathRelative = Utilities.getSourceFilePath(group.getParent(), pathElement.getParent());
      if (cyclicFilePathRelative == null) {
        LOG.error("Failed to determine relative path within system for cycleGroupParent '" + group.getParent() + "' and source file '" + pathElement.getParent() + "'");
        continue;
      }

      String cyclicFilePathAbsolute = null;
      try {
        cyclicFilePathAbsolute = new File(this.sonargraphBasePath, cyclicFilePathRelative).getCanonicalPath().replace('\\', '/');
      } catch (IOException e1) {
        LOG.error("Failed to determine absolute path for '" + cyclicFilePathRelative + "'", e1);
      }

      if (cyclicFilePathAbsolute != null) {
        org.sonar.api.resources.File srcFile = org.sonar.api.resources.File.fromIOFile(new File(cyclicFilePathAbsolute), project);
        if (srcFile != null) {
          srcFiles.add(srcFile);
        } else {
          LOG.error("Failed to resolve resource of '" + cyclicFilePathAbsolute + "'");
        }
      }
    }

    return srcFiles;
  }

  private boolean handlePackageCycleGroup(XsdCycleGroup group, FilePredicates predicates) {
    List<Resource> srcDirectories = determineCyclicSrcDirectories(group, predicates);
    boolean issueAddedForAllPackages = true;

    // No source directories are detected for class files
    for (Resource jPackage : srcDirectories) {
      issueAddedForAllPackages = addCycleIssue(jPackage, srcDirectories) || issueAddedForAllPackages;
    }
    return issueAddedForAllPackages;
  }

  private List<Resource> determineCyclicSrcDirectories(XsdCycleGroup group, FilePredicates predicates) {
    List<Resource> packages = new ArrayList<Resource>();
    for (XsdCyclePath pathElement : group.getCyclePath()) {
      String cyclicPath;
      try {
        cyclicPath = new File(this.sonargraphBasePath, pathElement.getParent()).getCanonicalPath().replace('\\', '/');
      } catch (IOException e1) {
        LOG.error("Failed to determine absolute path for '" + pathElement.getParent() + "'", e1);
        return Collections.emptyList();
      }

      Set<String> srcDirs = getSourceDirectories(predicates, cyclicPath);
      if (srcDirs.isEmpty()) {
        LOG.debug("Could not locate src directory for '" + pathElement.getParent() + "'");
        continue;
      }
      if (srcDirs.size() > 1) {
        LOG.warn("Found more than one src directory for '" + pathElement.getParent() + "'");
      }

      for (String path : srcDirs) {
        Directory srcDirectory = org.sonar.api.resources.Directory.fromIOFile(new File(path), project);
        if (srcDirectory != null) {
          packages.add(srcDirectory);
        } else {
          LOG.warn("Failed to resolve path '" + path + "' to directory.");
        }
      }
    }
    return packages;
  }

  private Set<String> getSourceDirectories(FilePredicates predicates, String cyclicPath) {
    Set<String> srcDirs = new HashSet<String>();

    for (File next : fileSystem.files(predicates.and())) {
      File dir = next.getParentFile();
      try {
        String canonicalPath = dir.getCanonicalPath();
        if (canonicalPath.replace('\\', '/').endsWith(cyclicPath)) {
          srcDirs.add(canonicalPath);
        }
      } catch (IOException e) {
        LOG.warn("Could not get canonical path for directory '" + dir.getAbsolutePath() + "'", e);
      }
    }
    return srcDirs;
  }

  private boolean addCycleIssue(Resource resource, List<Resource> involvedResources) {
    Issuable issuable = perspectives.as(Issuable.class, resource);
    if (issuable == null) {
      // omit cyclic directory that is part of package of java class folder
      return false;
    }
    IssueBuilder issueBuilder = issuable.newIssueBuilder();
    issueBuilder.severity(rule.getSeverity().toString()).ruleKey(rule.getRule().ruleKey());

    List<Resource> tempInvolvedResources = new ArrayList<Resource>(involvedResources);
    tempInvolvedResources.remove(resource);
    StringBuilder builder = new StringBuilder();
    if (resource instanceof Directory) {
      builder.append("Package participates in a cycle group");
    } else {
      builder.append("File participates in a cycle group");
    }
    boolean first = true;
    for (Resource next : tempInvolvedResources) {
      if (first) {
        if (resource instanceof Directory) {
          builder.append(" with package(s): ").append(next.getName());
        } else {
          builder.append(" with source file(s): ").append(next.getLongName());
        }
        first = false;
      } else {
        if (resource instanceof Directory) {
          builder.append(", ").append(next.getName());
        } else {
          builder.append(", ").append(next.getLongName());
        }
      }
    }
    issueBuilder.message(builder.toString());
    // An issue is attached to each of the packages involved in the cycle group.
    // The number of cycle group warnings in the issues drill-down can therefore differ
    // from the number given in Sonargraph Architect dashbox in the components dashboard.
    issuable.addIssue(issueBuilder.build());
    return true;
  }
}
