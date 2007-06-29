/*
 * User: anna
 * Date: 26-Jun-2007
 */
package com.intellij.codeInsight;

import com.intellij.CommonBundle;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectBundle;
import com.intellij.openapi.projectRoots.ProjectRootType;
import com.intellij.openapi.projectRoots.SdkModificator;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiFormatUtil;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ArrayUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.ui.OptionsMessageDialog;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.IOException;
import java.util.List;

public class ExternalAnnotationsManagerImpl extends ExternalAnnotationsManager {
  private static final Logger LOG = Logger.getInstance("#" + ExternalAnnotationsManagerImpl.class.getName());

  @NonNls private static final String EXTERNAL_ANNOTATIONS_PROPERTY = "ExternalAnnotations";

  @Nullable
  public PsiAnnotation findExternalAnnotation(final PsiModifierListOwner listOwner, final String annotationFQN) {
    final XmlFile xmlFile = findExternalAnnotationsFile(listOwner);
    if (xmlFile != null) {
      final XmlDocument document = xmlFile.getDocument();
      if (document != null) {
        final XmlTag rootTag = document.getRootTag();
        if (rootTag != null) {
          final String externalName = PsiFormatUtil.getExternalName(listOwner);
          for (final XmlTag tag : rootTag.getSubTags()) {
            final String className = tag.getAttributeValue("name");
            if (Comparing.strEqual(className, externalName)) {
              for (XmlTag annotationTag : tag.getSubTags()) {
                if (Comparing.strEqual(annotationTag.getAttributeValue("name"), annotationFQN)) {
                  StringBuffer buf = new StringBuffer();
                  for (XmlTag annotationaParameter : annotationTag.getSubTags()) {
                    buf.append(",").append(annotationaParameter.getAttributeValue("name")).append("=")
                      .append(annotationaParameter.getAttributeValue("value"));
                  }
                  final String annotationText =
                    "@" + annotationFQN + (buf.length() > 0 ? "(" + StringUtil.trimStart(buf.toString(), ",") + ")" : "");
                  try {
                    return listOwner.getManager().getElementFactory().createAnnotationFromText(annotationText, null);
                  }
                  catch (IncorrectOperationException e) {
                    LOG.error(e);
                  }
                }
              }
              break;
            }
          }
        }
      }
    }
    return null;
  }


  public void annotateExternally(final PsiModifierListOwner listOwner, final String annotationFQName) {
    final Project project = listOwner.getProject();
    final PsiManager psiManager = listOwner.getManager();
    XmlFile xmlFile = findExternalAnnotationsFile(listOwner);
    if (xmlFile != null) {
      if (!CodeInsightUtil.prepareFileForWrite(xmlFile)) return;
      annotateExternally(listOwner, annotationFQName, xmlFile);
    }
    else {
      final PsiFile containingFile = listOwner.getContainingFile();
      if (containingFile instanceof PsiJavaFile) {
        final String packageName = ((PsiJavaFile)containingFile).getPackageName();
        final VirtualFile virtualFile = containingFile.getVirtualFile();
        LOG.assertTrue(virtualFile != null);
        final List<OrderEntry> entries = ProjectRootManager.getInstance(project).getFileIndex().getOrderEntriesForFile(virtualFile);
        if (!entries.isEmpty()) {
          final OrderEntry entry = entries.get(0);
          if (ApplicationManager.getApplication().isUnitTestMode() || ApplicationManager.getApplication().isHeadlessEnvironment()) return;
          SwingUtilities.invokeLater(new Runnable() {
            public void run() {
              final FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor();
              descriptor.setTitle(ProjectBundle.message("external.annotations.root.chooser.title", entry.getPresentableName()));
              descriptor.setDescription(ProjectBundle.message("external.annotations.root.chooser.description"));
              final VirtualFile[] files = FileChooser.chooseFiles(project, descriptor);
              if (files.length > 0) {
                new WriteCommandAction(project, null) {
                  protected void run(final Result result) throws Throwable {
                    if (files[0] != null) {
                      appendChosenAnnotationsRoot(entry, files[0]);
                      annotateExternally(listOwner, annotationFQName, createAnnotationsXml(psiManager, files[0], packageName));
                    }
                  }
                }.execute();
              }
            }
          });
        }
      }
    }
  }

  public boolean useExternalAnnotations(@NotNull final PsiElement element) {
    if (!element.getManager().isInProject(element)) return true;
    final Project project = element.getProject();
    final VirtualFile virtualFile = element.getContainingFile().getVirtualFile();
    LOG.assertTrue(virtualFile != null);
    final List<OrderEntry> entries = ProjectRootManager.getInstance(project).getFileIndex().getOrderEntriesForFile(virtualFile);
    for (OrderEntry entry : entries) {
      final String[] urls = entry.getUrls(OrderRootType.ANNOTATIONS);
      if (urls.length > 0) {
        return true;
      }
    }
    final MyExternalPromptDialog dialog = new MyExternalPromptDialog(project);
    if (dialog.isToBeShown()) {
      dialog.show();
      if (dialog.isOK()) {
        return true;
      }
    }
    return false;
  }

  private static void appendChosenAnnotationsRoot(final OrderEntry entry, final VirtualFile vFile) {
    if (entry instanceof LibraryOrderEntry) {
      Library library = ((LibraryOrderEntry)entry).getLibrary();
      LOG.assertTrue(library != null);
      final ModifiableRootModel rootModel = ModuleRootManager.getInstance(entry.getOwnerModule()).getModifiableModel();
      final Library.ModifiableModel model = library.getModifiableModel();
      model.addRoot(vFile, OrderRootType.ANNOTATIONS);
      model.commit();
      rootModel.commit();
    }
    else if (entry instanceof ModuleSourceOrderEntry) {
      final ModifiableRootModel model = ModuleRootManager.getInstance(entry.getOwnerModule()).getModifiableModel();
      model.setAnnotationUrls(ArrayUtil.mergeArrays(model.getAnnotationUrls(), new String[]{vFile.getUrl()}, String.class));
      model.commit();
    }
    else if (entry instanceof JdkOrderEntry) {
      final SdkModificator sdkModificator = ((JdkOrderEntry)entry).getJdk().getSdkModificator();
      sdkModificator.addRoot(vFile, ProjectRootType.ANNOTATIONS);
      sdkModificator.commitChanges();
    }
  }

  private static void annotateExternally(final PsiModifierListOwner listOwner,
                                         final String annotationFQName,
                                         final @Nullable XmlFile xmlFile) {
    if (xmlFile == null) return;
    try {
      final XmlDocument document = xmlFile.getDocument();
      if (document != null) {
        final XmlTag rootTag = document.getRootTag();
        final String externalName = PsiFormatUtil.getExternalName(listOwner);
        if (rootTag != null) {
          for (XmlTag tag : rootTag.getSubTags()) {
            if (Comparing.strEqual(tag.getAttributeValue("name"), externalName)) {
              tag.add(xmlFile.getManager().getElementFactory().createTagFromText("<annotation name=\'" + annotationFQName + "\'/>"));
              return;
            }
          }
          final @NonNls String text =
            "<item name=\'" + externalName + "\'>\n" + "  <annotation name=\'" + annotationFQName + "\'/>\n" + "</item>";
          rootTag.add(xmlFile.getManager().getElementFactory().createTagFromText(text));
        }
      }
    }
    catch (IncorrectOperationException e) {
      LOG.error(e);
    }
  }

  @Nullable
  private static XmlFile createAnnotationsXml(PsiManager psiManager, VirtualFile root, String packageName) {
    final String[] dirs = packageName.split("[\\.]");
    for (String dir : dirs) {
      VirtualFile subdir = root.findChild(dir);
      if (subdir == null) {
        try {
          subdir = root.createChildDirectory(null, dir);
        }
        catch (IOException e) {
          LOG.error(e);
        }
      }
      root = subdir;
    }
    final PsiDirectory directory = psiManager.findDirectory(root);
    if (directory == null) return null;

    final PsiFile psiFile = directory.findFile(ANNOTATIONS_XML);
    if (psiFile instanceof XmlFile) {
      return (XmlFile)psiFile;
    }

    try {
      return (XmlFile)directory.add(psiManager.getElementFactory().createFileFromText(ANNOTATIONS_XML, "<root></root>"));
    }
    catch (IncorrectOperationException e) {
      LOG.error(e);
    }
    return null;
  }

  @Nullable
  private static XmlFile findExternalAnnotationsFile(PsiModifierListOwner listOwner) {
    final Project project = listOwner.getProject();
    final PsiManager psiManager = PsiManager.getInstance(project);
    final PsiFile containingFile = listOwner.getContainingFile();
    if (containingFile instanceof PsiJavaFile) {
      final PsiJavaFile javaFile = (PsiJavaFile)containingFile;
      final String packageName = javaFile.getPackageName();
      final PsiPackage psiPackage = psiManager.findPackage(packageName);
      if (psiPackage != null) {
        final PsiDirectory[] dirsWithExternalAnnotations = psiPackage.getDirectories();
        for (final PsiDirectory directory : dirsWithExternalAnnotations) {
          final PsiFile psiFile = directory.findFile(ANNOTATIONS_XML);
          if (psiFile instanceof XmlFile) {
            return (XmlFile)psiFile;
          }
        }
      }
      final VirtualFile virtualFile = containingFile.getVirtualFile();
      if (virtualFile != null) {
        final List<OrderEntry> entries = ProjectRootManager.getInstance(project).getFileIndex().getOrderEntriesForFile(virtualFile);
        if (!entries.isEmpty()) {
          final VirtualFile[] externalFiles = entries.get(0).getFiles(OrderRootType.ANNOTATIONS);
          for (VirtualFile file : externalFiles) {
            final VirtualFile ext = file.getFileSystem().findFileByPath(file.getPath() + "/" + packageName.replace(".", "/") + "/" + ANNOTATIONS_XML);
            if (ext != null) {
              final PsiFile psiFile = psiManager.findFile(ext);
              if (psiFile instanceof XmlFile) {
                return (XmlFile)psiFile;
              }
            }
          }
        }
      }
    }
    /*final VirtualFile virtualFile = containingFile.getVirtualFile(); //for java files only
    if (virtualFile != null) {
      final VirtualFile parent = virtualFile.getParent();
      if (parent != null) {
        final VirtualFile extFile = parent.findChild(ANNOTATIONS_XML);
        if (extFile != null) {
          return (XmlFile)psiManager.findFile(extFile);
        }
      }
    }*/

    return null;
  }

  private static class MyExternalPromptDialog extends OptionsMessageDialog {
    private final Project myProject;

    public MyExternalPromptDialog(final Project project) {
      super(project, ProjectBundle.message("external.annotations.suggestion.message"), ProjectBundle.message("external.annotation.prompt"), Messages.getQuestionIcon());
      myProject = project;
      init();
    }

    protected String getOkActionName() {
      return CommonBundle.getOkButtonText();
    }

    protected String getCancelActionName() {
      return CommonBundle.getCancelButtonText();
    }

    protected boolean isToBeShown() {
      if (ApplicationManager.getApplication().isHeadlessEnvironment() || ApplicationManager.getApplication().isUnitTestMode()) return false;
      final String value = PropertiesComponent.getInstance(myProject).getValue(EXTERNAL_ANNOTATIONS_PROPERTY);
      return value == null || Boolean.valueOf(value).booleanValue();
    }

    protected void setToBeShown(boolean value, boolean onOk) {
      PropertiesComponent.getInstance(myProject).setValue(EXTERNAL_ANNOTATIONS_PROPERTY, String.valueOf(value));
    }

    protected boolean shouldSaveOptionsOnCancel() {
      return true;
    }
  }
}