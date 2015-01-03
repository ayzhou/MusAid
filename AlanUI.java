

import abc.notation.*;
import abc.parser.TuneBookParser;
import abc.ui.swing.JScoreComponent;
import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.io.jvm.JVMAudioInputStream;
import be.tarsos.dsp.pitch.PitchDetectionHandler;
import be.tarsos.dsp.pitch.PitchDetectionResult;
import be.tarsos.dsp.pitch.PitchProcessor;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;

import java.awt.Container;
import java.awt.event.*;
import java.io.File;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Vector;

import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.pitch.PitchProcessor.PitchEstimationAlgorithm;

import java.util.HashMap;


/**
 * Created by Alan on 11/7/2014.
 */
public class AlanUI  extends JPanel implements ActionListener, PitchDetectionHandler {

    //references to the various Swing elements that are needed
    private JFileChooser chooser;
    private File abcFile;
    private JScoreComponent jscore;
    private JFrame frame;
    private JButton openFile;
    private JButton displaySong;
    private JPanel tempoPanel;
    private JFormattedTextField tempoField;
    private JFormattedTextField referenceButtonField;
    private JButton referenceButton;

    //number of the abc song that we are looking at in the file
    private int referenceNumber;

    //more Swing elements
    private JButton goButton;
    private JButton playButton;
    private JButton restartButton;

    //iterator for the Music elements in the song
    private Iterator it;

    //the part of the song that we are looking to record
    private Voice voice;

    //current song
    private Tune tune;

    //reference to this object
    private AlanUI alan;

    //tempo at which to sing
    private int tempo;

    //Java Sound elements
    private AudioDispatcher dispatcher;
    private Mixer currentMixer;
    private TargetDataLine targetLine;

    private boolean playing;

    //which algorithm are we using?
    private PitchEstimationAlgorithm algo;

    //thread that listened to the user input
    private Thread audioThread;

    //current pitch
    private float pitch;

    //current accidental
    private Accidental accidental;

    //count of pitches for current note
    private HashMap<Integer, Integer> pitches = new HashMap<Integer, Integer>();

    //set of constants that represent accidentals
    private HashSet<Integer> accidentalPitches = new HashSet<Integer>();

    //current keysignature we are using
    private KeySignature keySig;

    //thread for dynamically repainting the song
    private dynamicPaintTask paintTask;

    //constants for placing the buttons
    GridBagConstraints gbc;

    //is song paused
    private boolean paused;

    public AlanUI () throws LineUnavailableException {
        super();
        alan = this;

        //add accidental pitches
        accidentalPitches.add(1);
        accidentalPitches.add(3);
        accidentalPitches.add(6);
        accidentalPitches.add(8);
        accidentalPitches.add(10);


        //initialize the Swing elements
        openFile = new JButton("Select abc file");
        openFile.addActionListener(this);
        JPanel openFilePanel = new JPanel();
        openFilePanel.add(openFile);

        JLabel refText = new JLabel("Reference number:");
        referenceButtonField = new JFormattedTextField(new Integer(1));
        referenceButtonField.setColumns(3);
        JPanel refPanel = new JPanel();
        referenceButton = new JButton("Set");
        referenceButton.addActionListener(this);
        refPanel.add(refText);
        refPanel.add(referenceButtonField);
        refPanel.add(referenceButton);
        referenceButton.setEnabled(false);


        displaySong = new JButton("Display song");
        displaySong.addActionListener(this);
        displaySong.setEnabled(false);
        JPanel displaySongPanel = new JPanel();
        displaySongPanel.add(displaySong);

        tempoField = new JFormattedTextField(new Integer(70));
        tempoField.setColumns(3);
        tempoPanel = new JPanel();
        JLabel text = new JLabel("Enter tempo:");
        tempoPanel.add(text);
        tempoPanel.add(tempoField);

        goButton = new JButton("Go!");
        goButton.addActionListener(this);
        goButton.setOpaque(true);
        goButton.setEnabled(false);
        JPanel goButtonPanel = new JPanel();
        goButtonPanel.add(goButton);

        setLayout(new GridBagLayout());

        gbc = new GridBagConstraints();
        gbc.gridx = 1;
        gbc.gridy = 1;
        gbc.weightx = 1;
        gbc.anchor = GridBagConstraints.WEST;

        super.add(openFilePanel, gbc);

        gbc.gridy++;
        super.add(refPanel, gbc);

        gbc.gridy++;
        super.add(displaySongPanel, gbc);

        gbc.gridy++;
        super.add(tempoPanel, gbc);

        gbc.gridy++;
        super.add(goButtonPanel, gbc);


        openFile.setAlignmentX(Container.LEFT_ALIGNMENT);
        refPanel.setAlignmentX(Container.LEFT_ALIGNMENT);
        displaySong.setAlignmentX(Container.LEFT_ALIGNMENT);
        tempoPanel.setAlignmentX(Container.LEFT_ALIGNMENT);
        goButton.setAlignmentX(Container.LEFT_ALIGNMENT);


        chooser = new JFileChooser();
        jscore = new JScoreComponent();

        //add all the elements to the jpanel
        super.add(jscore);

        //initialize java sound

        float sampleRate = 44100;
        int bufferSize = 2048;
        int overlap = 1536;

        final AudioFormat format = new AudioFormat(sampleRate, 16, 1, true,
                true);
        final DataLine.Info dataLineInfo = new DataLine.Info(
                TargetDataLine.class, format);
        TargetDataLine line;
        line = (TargetDataLine) AudioSystem.getLine(dataLineInfo);
        targetLine = line;
        final int numberOfSamples = bufferSize;
        line.open(format, numberOfSamples);
        final AudioInputStream stream = new AudioInputStream(line);

        JVMAudioInputStream audioStream = new JVMAudioInputStream(stream);
        // create a new dispatcher
        dispatcher = new AudioDispatcher(audioStream, bufferSize,
                overlap);

        algo = PitchEstimationAlgorithm.YIN;
        // add a processor
        dispatcher.addAudioProcessor(new PitchProcessor(algo, sampleRate, bufferSize, this));

        //start a thread to listen to user
        audioThread = new Thread(dispatcher, "audio dispatching");
        audioThread.start();

    }

    //listens to the user for designated milliseconds
    private void listenFor (long milliseconds) throws InterruptedException {
        targetLine.start();
        Thread.sleep(milliseconds);
        targetLine.stop();
        return;

    }

    //creates the jframe that holds all the elements and displays it
    private void createAndShowGui() {
        frame = new JFrame("MusAid");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.getContentPane().add(this);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    //displays the selected song. Called when the display button is clicked
    private void showSong() {
        TuneBookParser parser = new TuneBookParser();
        TuneBook tb;
        try {
            tb = parser.parse(abcFile);
        }
        catch (IOException e) {
            return;
        }
        tune = tb.getTune(referenceNumber); //retrieve the tune to display from the tunebook using its reference number.

        //gets all the properties of that song and sets it to proper variables
        keySig = tune.getKey();
        jscore.setTune(tune);
        voice = tune.getMusic().getFirstVoice();
        it = voice.iterator();

        //enable go button
        goButton.setEnabled(true);

        //add pause & restart buttons
        addPauseRestartButtons();

        //repaint
        // super.add(jscore);
        super.repaint();
        frame.pack();
        frame.repaint();

        //unpause
        paused = false;
    }


    //does all the work. DoInBackground() is called whenever the user clicks Go.
    class dynamicPaintTask extends
            SwingWorker {
        @Override
        public Object doInBackground() {

            playing = true;
            playButton.setEnabled(true);
            restartButton.setEnabled(true);

            //chord for the last note
            MultiNote lastNote = null;

            //index of last note
            int lastIndex = -1;

            //how long a quarter note shoud last
            double quarterLength = 60/(double) tempo;

            //loop for the notes in the song
            while(it.hasNext()) {
                //checks if the song is paused
                while (paused) {
                    try {
                        Thread.sleep(100);
                    }
                    catch (InterruptedException e) {};
                }

                //gets current note
                MusicElement currentScoreEl = (MusicElement) it.next();
                //if it's not a note, it's something we don't care about. skip to next element
                if (!(currentScoreEl instanceof Note)){
                    continue;
                }

                //duration of the note
                long timeToRest = (long) (1000* quarterLength*((Note) currentScoreEl).getDuration()/Note.QUARTER);
                if (currentScoreEl instanceof Note) {

                    //if it's a rest, wait for the duration and then go on to the next note
                    if (((Note)currentScoreEl).getHeight() == Note.REST) {
                        try {
                            Thread.sleep(timeToRest);
                            continue;
                        }
                        catch (Exception e) {

                        }
                    }

                    //vector for the MultiNote. represents the feedback
                    Vector vector = new Vector();

                    //index of current note
                    int index = voice.indexOf(currentScoreEl);

                    //paint current note and last note
                    Note note = null;
                    try {
                        //paints current note yellow
                        note = (Note) currentScoreEl.clone();
                        note.setColor(Color.YELLOW);
                        voice.addElementAt(note, index);

                        //paints the feedback for the user. Green if correct, red and black if incorrect
                        if (lastNote != null && lastIndex != -1) {
                            voice.addElementAt(lastNote, lastIndex);
                        }
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }

                    //redraw
                    jscore.setTune(tune);
                    alan.add(jscore);
                    alan.repaint();
                    frame.pack();
                    frame.repaint();

                    //listen for next note
                    try {
                        listenFor((int) timeToRest);
                    }
                    catch (Exception e) {
                        return null;
                    }

                    //find most frequently occurring pitch
                    findMostFrequentPitch();

                    //add original note to feedback
                    vector.add(currentScoreEl);
                    if (lastNote != null){
                        try {
                            if ((pitch > 60 && pitch < 1000)) {
                                //if user is correct, paint it green
                                if (noteMatches((Note) currentScoreEl)) {
                                    ((Note)currentScoreEl).setColor(Color.green);
                                }
                                //if user is incorrect, leave it black and add in the user's pitch to the vector
                                else {
                                    Note note1 = new Note(Note.c);
                                    note.setDuration(((Note) currentScoreEl).getDuration());
                                    note1.setHeight(frequencyToPitch(pitch)[0]);
                                    note1.setAccidental(accidental);
                                    note1.setColor(Color.RED);
                                    vector.add(note1);
                                }

                            }
                            //reset pitch, accidentals, and pitch count
                            pitch = 0;
                            accidental = Accidental.NATURAL;
                            pitches = new HashMap<Integer, Integer>();
                        }
                        catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    //create a multinote for the user feedback
                    MultiNote multi = new MultiNote(vector);

                    //save these for painting in the next iteration
                    lastNote =  multi;
                    lastIndex = index;
                }
            }
            playing = false;
            return null;
        }
    }

    //takes frequency in hertz, sets accidental to calculated accidental, and returns a byte array in which
    //the first element is the pitch constant
    private byte[] frequencyToPitch(float frequency) {
        byte[] pitch = new byte[2];

        //convert to pitch constant
        double rPitch = 69 + 12 * Math.log(frequency/440.0)/Math.log(2);

        //convert to abc4j constants
        pitch[0] = (byte) Math.round(rPitch-60);

        //calculate accidental value
        if (accidentalPitches.contains(Integer.valueOf((int) pitch[0]) % 12)) {
            if (keySig.hasOnlySharps()) {
                pitch[0] = (byte) (pitch[0] - 1);
                accidental = Accidental.SHARP;
            }
            else {
                pitch[0] = (byte) (pitch[0]+1);
                accidental = Accidental.FLAT;
            }
        }
        return pitch;
    }

    //add pause and restart buttons
    private void addPauseRestartButtons() {
        gbc.gridy = 0;
        playButton = new JButton("Pause");
        playButton.addActionListener(this);
        playButton.setOpaque(true);
        playButton.setEnabled(false);

        restartButton = new JButton("Restart");
        restartButton.addActionListener(this);
        restartButton.setOpaque(true);
        restartButton.setEnabled(false);

        JPanel buttonsPanel = new JPanel();
        buttonsPanel.add(playButton);
        buttonsPanel.add(restartButton);

        super.add(buttonsPanel, gbc);

        buttonsPanel.setAlignmentX(Container.LEFT_ALIGNMENT);

    }

    //determine if the note given matches the calculated note
    private boolean noteMatches(Note note) {
        byte p = frequencyToPitch(pitch)[0];
        Accidental acc = keySig.getAccidentalFor(note.getStrictHeight());
        if (note.getHeight() == p && accidental == note.getAccidental(keySig)) {
            return true;
        }
        return false;

    }

    //action handler for the buttons
    public void  actionPerformed (ActionEvent e){
        Object obj = e.getSource();

        //unpauses
        if (paused) {
            if (obj == playButton) {
                paused = false;
                return;
            }
        }
        //pauses
        if (!paused) {
            if (obj == playButton) {
                paused = true;
            }
        }
        //restarts entire program
            if (obj == restartButton) {
                //remove current components
                Component[] cArray = super.getComponents();
                for (Component c : cArray) {
                    super.remove(c);
                }

                //cancel dynamic painting thread
                paintTask.cancel(true);

                //add completely new references
                openFile = new JButton("Select abc file");
                openFile.addActionListener(this);
                JPanel openFilePanel = new JPanel();
                openFilePanel.add(openFile);

                JLabel refText = new JLabel("Reference number:");
                referenceButtonField = new JFormattedTextField(new Integer(1));
                referenceButtonField.setColumns(3);
                JPanel refPanel = new JPanel();
                referenceButton = new JButton("Set");
                referenceButton.addActionListener(this);
                refPanel.add(refText);
                refPanel.add(referenceButtonField);
                refPanel.add(referenceButton);
                referenceButton.setEnabled(false);


                displaySong = new JButton("Display song");
                displaySong.addActionListener(this);
                displaySong.setEnabled(false);
                JPanel displaySongPanel = new JPanel();
                displaySongPanel.add(displaySong);

                tempoField = new JFormattedTextField(new Integer(70));
                tempoField.setColumns(3);
                tempoPanel = new JPanel();
                JLabel text = new JLabel("Enter tempo:");
                tempoPanel.add(text);
                tempoPanel.add(tempoField);

                goButton = new JButton("Go!");
                goButton.addActionListener(this);
                goButton.setOpaque(true);
                goButton.setEnabled(false);
                JPanel goButtonPanel = new JPanel();
                goButtonPanel.add(goButton);

                setLayout(new GridBagLayout());

                gbc = new GridBagConstraints();
                gbc.gridx = 1;
                gbc.gridy = 1;
                gbc.weightx = 1;
                gbc.anchor = GridBagConstraints.WEST;

                super.add(openFilePanel, gbc);

                gbc.gridy++;
                super.add(refPanel, gbc);

                gbc.gridy++;
                super.add(displaySongPanel, gbc);

                gbc.gridy++;
                super.add(tempoPanel, gbc);

                gbc.gridy++;
                super.add(goButtonPanel, gbc);


                openFile.setAlignmentX(Container.LEFT_ALIGNMENT);
                refPanel.setAlignmentX(Container.LEFT_ALIGNMENT);
                displaySong.setAlignmentX(Container.LEFT_ALIGNMENT);
                tempoPanel.setAlignmentX(Container.LEFT_ALIGNMENT);
                goButton.setAlignmentX(Container.LEFT_ALIGNMENT);

                frame.setVisible(false);

                createAndShowGui();

                playing = false;
                paused=true;

            return;
        }
        //shows the file chooser
        if (obj == openFile) {
            FileNameExtensionFilter filter = new FileNameExtensionFilter(
                    "ABC files", "abc");
            chooser.setFileFilter(filter);
            int returnVal = chooser.showOpenDialog(this);

            if (returnVal == JFileChooser.APPROVE_OPTION) {
                abcFile = chooser.getSelectedFile();
                referenceButton.setEnabled(true);
                //This is where a real application would open the file.
            } else {
                return;
            }
        }
        //displays the song
        else if (obj == displaySong) {
            this.showSong();
        }
        //executes the dynamic feedback
        else if (obj == goButton) {
            tempo = (Integer) tempoField.getValue();
            paintTask = new dynamicPaintTask();
            paintTask.execute();
        }
        //get the reference number
        else if (obj == referenceButton) {
            referenceNumber = (Integer) referenceButtonField.getValue();
            displaySong.setEnabled(true);
        }

    }

    //this is called whenever a pitch is detected by the yin algorithm
    public void handlePitch(PitchDetectionResult pitchDetectionResult,AudioEvent audioEvent) {
        if(pitchDetectionResult.getPitch() != -1){
            double timeStamp = audioEvent.getTimeStamp();
            double p = pitchDetectionResult.getPitch();
            int pitch = (int) pitchDetectionResult.getPitch();

            //sets the count properly in the hashtable
            int count = 0;
            if (pitches.containsKey(pitch)) {
                count = pitches.get(pitch);
            }
            if (pitch > 50) {
                pitches.put(pitch, count + 1);
            }
            float probability = pitchDetectionResult.getProbability();
            double rms = audioEvent.getRMS() * 100;
        }
    }

    //calculate most frequently occuring pitch in hashtable
    private void findMostFrequentPitch() {
        int max = 0;

        for (Integer i : pitches.keySet()) {
            if (pitches.get(i) > max) {
                max = pitches.get(i);
                pitch = i;
            }
        }

    }

    //executes the program
    public static void main(String[] args){
        SwingUtilities.invokeLater(new Runnable() {
            public void run(){
                try {
                    new AlanUI().createAndShowGui();
                }
                catch(Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

}
