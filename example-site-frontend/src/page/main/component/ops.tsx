/* eslint-disable no-console */
import React, {useState, useEffect} from "react";
import {Button, Select, Input} from "antd";
import "./ops.css";
import {ExampleAJAXWebService} from "service/ExampleAJAXWebService";

const {Option} = Select;

export const Ops = () => {
    const [suggestions, setSuggestions] = useState<string[]>([]);
    const [textContent, setTextContent] = useState("");
    const [textContents, setTextContents] = useState<string[]>(["", "", "", "", ""]);
    const [displayedTexts, setDisplayedTexts] = useState<string[]>(["", "", "", "", ""]);
    const [currentIndexes, setCurrentIndexes] = useState<number[]>([0, 0, 0, 0, 0]);
    const [imageUrl, setImageUrl] = useState("");
    const [videoId, setVideoId] = useState("");
    const [videoUrl, setVideoUrl] = useState("");
    const [idea, setIdea] = useState("");
    const [location, setLocation] = useState("UWS");
    const [isLoading, setIsLoading] = useState(false);
    const [checkVideoInterval, setCheckVideoInterval] = useState<NodeJS.Timeout | null>(null);
    const [videoProgress, setVideoProgress] = useState<number | null>(null);
    const [generateVideo, setGenerateVideo] = useState(true);

    const fetchSuggestions = async () => {
        const rsp = await ExampleAJAXWebService.idea();
        setSuggestions(rsp.ideas);
    };

    const submit = async () => {
        setTextContent("");
        setImageUrl("");
        setVideoUrl("");
        setVideoProgress(null);
        setVideoId("");
        setIsLoading(true);
        setTextContents(["", "", "", "", ""]);
        setDisplayedTexts(["", "", "", "", ""]);
        setCurrentIndexes([0, 0, 0, 0, 0]);
        try {
            const rsp = await ExampleAJAXWebService.createPost({idea, location, is_generate_video: generateVideo});
            setTextContent(rsp.content ?? "");
            setTextContents(rsp.contents);
            setImageUrl(rsp.image_url);
            setVideoId(rsp.video_url ?? "");
        } finally {
            setIsLoading(false);
        }
    };

    useEffect(() => {
        if (videoId) {
            const interval = setInterval(async () => {
                try {
                    const response = await ExampleAJAXWebService.getGenmo(videoId);
                    if (response.status === "SUCCESS") {
                        setVideoUrl(response.url ?? "");
                        setVideoProgress(1);
                        clearInterval(interval);
                        setCheckVideoInterval(null);
                    } else if ("progress" in response && response.progress !== null && response.progress !== undefined) {
                        setVideoProgress(parseFloat(response.progress.toFixed(2)));
                    }
                } catch (error) {
                    console.error("Error checking video status:", error);
                }
            }, 3000);

            setCheckVideoInterval(interval);
        }

        return () => {
            if (checkVideoInterval) {
                clearInterval(checkVideoInterval);
            }
        };
    }, [videoId]);

    useEffect(() => {
        const timers: NodeJS.Timeout[] = [];

        textContents.forEach((textContent, index) => {
            if (currentIndexes[index] < textContent.length) {
                const timer = setTimeout(() => {
                    const updatedDisplayedTexts = [...displayedTexts];
                    updatedDisplayedTexts[index] += textContent[currentIndexes[index]];
                    setDisplayedTexts(updatedDisplayedTexts);

                    const updatedCurrentIndexes = [...currentIndexes];
                    updatedCurrentIndexes[index]++;
                    setCurrentIndexes(updatedCurrentIndexes);
                }, 3); // Adjust speed here

                timers.push(timer);
            }
        });

        return () => {
            timers.forEach(clearTimeout);
        };
    }, [textContents, currentIndexes]);

    useEffect(() => {
        fetchSuggestions();
    }, []);

    const handleSuggestionClick = (suggestion: string) => {
        setIdea(suggestion);
    };

    const handleLocationChange = (value: string) => {
        setLocation(value);
    };

    return (
        <div className="container">
            <div className="top-section">
                <div className="idea">
                    <label>Idea:</label>
                    <Input type="text" value={idea} onChange={e => setIdea(e.target.value)} />
                </div>
                <div className="location">
                    <label>Location:</label>
                    <Select defaultValue={location} onChange={handleLocationChange} style={{width: 150}}>
                        <Option value="UWS">UWS</Option>
                        <Option value="Westfield">Westfield</Option>
                        <Option value="UES">UES</Option>
                        <Option value="Chelsea">Chelsea</Option>
                        <Option value="Downtown Brooklyn">Downtown Brooklyn</Option>
                        <Option value="Springfield">Springfield</Option>
                        <Option value="Quakertown">Quakertown</Option>
                    </Select>
                </div>

                <div className="generate-video">
                    <label>Generate Video:</label>
                    <Select defaultValue={generateVideo.toString()} onChange={value => setGenerateVideo(value === "true")} style={{width: 150}}>
                        <Option value="true">Yes</Option>
                        <Option value="false">No</Option>
                    </Select>
                </div>

                <Button onClick={submit} loading={isLoading}>
                    AI Generate
                </Button>
            </div>

            <div className="suggestions">
                <label>Suggestion:</label>
                <div className="suggestion-buttons">
                    {suggestions.map((suggestion, index) => (
                        <Button key={index} className="suggestion-button" onClick={() => handleSuggestionClick(suggestion)}>
                            {suggestion}
                        </Button>
                    ))}
                </div>
            </div>

            <div className="main-content">
                <label>Generated Posts:</label>
                <div>
                    {displayedTexts.map((content, index) => (
                        <div key={index} className="text-area">
                            <div>{content}</div>
                        </div>
                    ))}
                </div>
                <div className="spacer" style={{height: "5px"}} />
                <label>Generated Image:</label>
                <div className="image-area">{imageUrl ? <img src={imageUrl} alt="Fetched from server" /> : <p>Loading image...</p>}</div>
                <label>Generated Video: {videoProgress !== null && <span style={{marginLeft: "8px"}}>{videoProgress * 100}%</span>}</label>
                <div className="video-area">
                    {videoUrl ? (
                        <video controls width="100%" height="auto">
                            <source src={videoUrl} type="video/mp4" />
                            Your browser does not support the video tag.
                        </video>
                    ) : (
                        <p>Loading video...</p>
                    )}
                </div>
            </div>
        </div>
    );
};
