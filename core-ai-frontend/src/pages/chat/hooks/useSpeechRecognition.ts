import { useEffect, useRef, useState, useCallback } from 'react';
import { fetchSpeechToken } from '../../../api/speech';

type SpeechSdkModule = typeof import('microsoft-cognitiveservices-speech-sdk');
type ConversationTranscriber = import('microsoft-cognitiveservices-speech-sdk').ConversationTranscriber;
type ConversationTranscriptionEventArgs = import('microsoft-cognitiveservices-speech-sdk').ConversationTranscriptionEventArgs;
type ConversationTranscriptionCanceledEventArgs = import('microsoft-cognitiveservices-speech-sdk').ConversationTranscriptionCanceledEventArgs;

let speechSdkPromise: Promise<SpeechSdkModule> | null = null;

function loadSpeechSdk(): Promise<SpeechSdkModule> {
  speechSdkPromise ??= import('microsoft-cognitiveservices-speech-sdk');
  return speechSdkPromise;
}

export interface TranscriptionSegment {
  id: string;
  speakerId: string;
  text: string;
  timestamp: number;  // ms since recognition start
}

interface UseSpeechRecognitionReturn {
  isListening: boolean;
  segments: TranscriptionSegment[];
  error: string | null;
  startListening: (language: string) => Promise<void>;
  stopListening: () => void;
  clearSegments: () => void;
}

export function useSpeechRecognition(): UseSpeechRecognitionReturn {
  const [isListening, setIsListening] = useState(false);
  const [segments, setSegments] = useState<TranscriptionSegment[]>([]);
  const [error, setError] = useState<string | null>(null);
  const recognizerRef = useRef<ConversationTranscriber | null>(null);
  const startTimeRef = useRef<number>(0);

  const stopListening = useCallback(() => {
    if (recognizerRef.current) {
      recognizerRef.current.stopTranscribingAsync(
        () => {
          recognizerRef.current?.close();
          recognizerRef.current = null;
          setIsListening(false);
        },
        (err: unknown) => {
          console.error('stopTranscribingAsync error:', err);
          recognizerRef.current?.close();
          recognizerRef.current = null;
          setIsListening(false);
        }
      );
    }
  }, []);

  const startListening = useCallback(async (language: string) => {
    // Stop any existing session first
    if (recognizerRef.current) {
      stopListening();
    }

    setError(null);
    try {
      const sdk = await loadSpeechSdk();
      const { token, region } = await fetchSpeechToken();

      const speechConfig = sdk.SpeechConfig.fromAuthorizationToken(token, region);
      speechConfig.speechRecognitionLanguage = language;
      speechConfig.setProperty(
        sdk.PropertyId.SpeechServiceConnection_InitialSilenceTimeoutMs,
        '5000'
      );

      const audioConfig = sdk.AudioConfig.fromDefaultMicrophoneInput();
      const transcriber = new sdk.ConversationTranscriber(speechConfig, audioConfig);

      startTimeRef.current = Date.now();

      transcriber.transcribed = (_sender: unknown, e: ConversationTranscriptionEventArgs) => {
        if (e.result.reason === sdk.ResultReason.RecognizedSpeech) {
          setSegments(prev => [...prev, {
            id: e.result.resultId,
            speakerId: e.result.speakerId || 'unknown',
            text: e.result.text.trim(),
            timestamp: Date.now() - startTimeRef.current,
          }]);
        }
      };

      transcriber.canceled = (_sender: unknown, e: ConversationTranscriptionCanceledEventArgs) => {
        console.warn('ConversationTranscriber canceled:', e.errorDetails);
        setError(e.errorDetails);
        setIsListening(false);
      };

      transcriber.sessionStopped = () => {
        setIsListening(false);
      };

      recognizerRef.current = transcriber;

      transcriber.startTranscribingAsync(
        () => { setIsListening(true); },
        (err: unknown) => {
          console.error('startTranscribingAsync error:', err);
          setError(String(err));
          setIsListening(false);
        }
      );
    } catch (err) {
      console.error('startListening error:', err);
      setError(err instanceof Error ? err.message : 'Failed to start recognition');
      setIsListening(false);
    }
  }, [stopListening]);

  const clearSegments = useCallback(() => {
    setSegments([]);
  }, []);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (recognizerRef.current) {
        recognizerRef.current.stopTranscribingAsync(
          () => recognizerRef.current?.close(),
          () => recognizerRef.current?.close()
        );
      }
    };
  }, []);

  return { isListening, segments, error, startListening, stopListening, clearSegments };
}
