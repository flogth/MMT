package info.kwarc.mmt.jedit

import info.kwarc.mmt.api.gui._

import java.awt._
import java.awt.event._
import javax.swing._
import javax.swing.ImageIcon
import javax.swing.event._

import org.gjt.sp.jedit._
import org.gjt.sp.jedit.msg._

/*
MMT toolbar with useful symbols and a clear button.
 */

class MMTToolBar(mmtp: MMTPlugin) extends JToolBar {
  private val controller = mmtp.controller

  private val insUS = Swing.Button("O") {
     val view = jEdit.getActiveView()
     Inserter.insertUSorTab(view.getTextArea())     
  }
  insUS.setToolTipText("Inserts object delimiter (US)")

  private val insRS = Swing.Button("D") {
     val view = jEdit.getActiveView()
     Inserter.insertRSReturn(view.getTextArea())
  }
  insRS.setToolTipText("Inserts declaration delimiter (RS)")
  
  private val insGS = Swing.Button("M") {
     val view = jEdit.getActiveView()
     Inserter.insertGSReturn(view.getTextArea())
  }
  insGS.setToolTipText("Inserts module delimiter (GS)")

  val clrIMG = (new ImageIcon(this.getClass().getResource("/images/clear_button.png"))).getImage()
  val clrIMGs = clrIMG.getScaledInstance(16, 16, java.awt.Image.SCALE_SMOOTH )
  private val clrBTN = new JButton("Clear", new ImageIcon(clrIMGs))
  clrBTN.setToolTipText("Clears MMT memory")
  clrBTN.addActionListener(new ActionListener() {
      def actionPerformed(e: ActionEvent) {
        controller.clear
      }
    }
  )

  val toolBar = new JToolBar("Symbol toolbar")
  //toolBar.setFloatable(false)
  add(insUS)
  add(insRS)
  add(insGS)
  add(clrBTN)
}

